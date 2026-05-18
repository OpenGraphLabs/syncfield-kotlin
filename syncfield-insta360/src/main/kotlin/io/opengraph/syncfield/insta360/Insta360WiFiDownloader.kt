package io.opengraph.syncfield.insta360

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.arashivision.sdkcamera.camera.InstaCameraManager
import com.arashivision.sdkcamera.camera.callback.ICameraOperateCallback
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeFormatter

internal object Insta360WiFiReachabilityPolicy {
    const val joinTimeoutMs: Long = 45_000L
    const val joinAttemptTimeoutMs: Long = 15_000L
    const val joinRetryDelayMs: Long = 2_000L
    const val joinAwaitSlackMs: Long = 2_000L

    val probeDelaysMs: List<Long> = listOf(
        1_000L,
        1_000L,
        1_500L,
        2_000L,
        2_500L,
        3_000L,
        4_000L,
        5_000L,
    )

    const val restoreTimeoutMs: Long = 4_000L
}

/**
 * Switches the phone onto an Insta360 camera AP, downloads a clip from
 * the SDK socket, and tears down the network request afterwards so the
 * device can reconnect to the user's home WiFi (or fall through to
 * cellular).
 *
 * Android's [ConnectivityManager.requestNetwork] + [WifiNetworkSpecifier]
 * is the API-29+ replacement for the deprecated
 * `WifiManager.enableNetwork`. The user is shown a system "use this
 * network?" dialog the first time we apply a specifier; this is a
 * platform constraint we can't bypass.
 *
 * Mirrors the iOS [Insta360WiFiDownloader.download] flow but uses the
 * Android NetworkRequest API and an HTTPS GET (the Insta360 OneSDK
 * Android variant exposes the camera's HTTP socket on the same
 * `192.168.42.1:6666` endpoint as the iOS SDK once we're routed through
 * the camera AP).
 */
class Insta360WiFiDownloader(private val context: Context) {

    private val cameraHost = "192.168.42.1"
    private val cameraPort = 6666

    data class BatchItem(
        val episodeDir: File,
        val streamId: String,
        val remoteFileURI: String,
        val destination: File,
        val bleAckMonotonicNs: Long,
        val sidecar: Insta360PendingSidecar? = null,
    )

    data class BatchResult(
        val item: BatchItem,
        val success: Boolean,
        val error: String? = null,
    )

    /**
     * Atomically: join camera AP -> probe reachability -> fetch clip ->
     * release network request.
     *
     * Returns the size in bytes of the downloaded file.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun download(
        remoteFileURI: String,
        destination: File,
        ssid: String,
        passphrase: String,
        sidecar: Insta360PendingSidecar? = null,
        progress: (Double) -> Unit,
    ): Long {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = applyNetworkSuggestion(cm, ssid, passphrase)
        try {
            waitForReachability(cm)
            var written = 0L
            for ((uri, dst) in resolveRemoteDestinations(
                cm = cm,
                remoteFileURI = remoteFileURI,
                destination = destination,
                sidecar = sidecar,
            )) {
                written += fetchResource(cm, uri, dst, progress)
            }
            return written
        } finally {
            releaseCameraNetwork(cm, callback)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun downloadBatch(
        ssid: String,
        passphrase: String,
        items: List<BatchItem>,
        onItemStart: (BatchItem) -> Unit,
        progress: (BatchItem, Double) -> Unit,
    ): List<BatchResult> {
        if (items.isEmpty()) return emptyList()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = applyNetworkSuggestion(cm, ssid, passphrase)
        return try {
            waitForReachability(cm)
            items.map { item ->
                runCatching {
                    onItemStart(item)
                    for ((uri, dst) in resolveRemoteDestinations(
                        cm = cm,
                        remoteFileURI = item.remoteFileURI,
                        destination = item.destination,
                        sidecar = item.sidecar,
                    )) {
                        fetchResource(
                            cm = cm,
                            remoteFileURI = uri,
                            destination = dst,
                            progress = { progress(item, it) },
                        )
                    }
                    BatchResult(item, success = true)
                }.getOrElse {
                    BatchResult(item, success = false, error = it.localizedMessage ?: it.toString())
                }
            }
        } finally {
            releaseCameraNetwork(cm, callback)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun listFiles(
        ssid: String,
        passphrase: String,
    ): List<Insta360FileInfo> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = applyNetworkSuggestion(cm, ssid, passphrase)
        return try {
            waitForReachability(cm)
            fetchCameraFileInfoList()
        } finally {
            closeCameraWifi()
            releaseCameraNetwork(cm, callback)
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun applyNetworkSuggestion(
        cm: ConnectivityManager,
        ssid: String,
        passphrase: String,
    ): ConnectivityManager.NetworkCallback {
        val deadlineMs = SystemClock.elapsedRealtime() + Insta360WiFiReachabilityPolicy.joinTimeoutMs
        var attempt = 1
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
            val attemptTimeoutMs = minOf(
                Insta360WiFiReachabilityPolicy.joinAttemptTimeoutMs,
                remainingMs,
            ).coerceAtLeast(1_000L)
            try {
                return requestCameraNetworkOnce(
                    cm = cm,
                    ssid = ssid,
                    passphrase = passphrase,
                    attempt = attempt,
                    timeoutMs = attemptTimeoutMs,
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                val retryDelayMs = minOf(
                    Insta360WiFiReachabilityPolicy.joinRetryDelayMs,
                    deadlineMs - SystemClock.elapsedRealtime(),
                )
                if (retryDelayMs <= 0) break
                InstaLog.log(
                    InstaLogCategory.WIFI,
                    level = InstaLogLevel.WARN,
                    event = "camera_ap_join_retry",
                    fields = mapOf(
                        "ssid" to ssid,
                        "attempt" to attempt,
                        "retry_delay_ms" to retryDelayMs,
                        "error" to (t.message ?: t::class.java.simpleName),
                    ),
                )
                delay(retryDelayMs)
                attempt += 1
            }
        }
        throw Insta360Error.HotspotApplyFailed(
            "camera AP unavailable for SSID=$ssid after ${Insta360WiFiReachabilityPolicy.joinTimeoutMs}ms" +
                (lastError?.message?.let { " ($it)" } ?: "")
        )
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun requestCameraNetworkOnce(
        cm: ConnectivityManager,
        ssid: String,
        passphrase: String,
        attempt: Int,
        timeoutMs: Long,
    ): ConnectivityManager.NetworkCallback {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val onAvailable = CompletableDeferred<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                runCatching { Insta360OneSDKBridge.bindNetwork(network) }
                InstaLog.log(
                    InstaLogCategory.WIFI,
                    event = "camera_ap_join_available",
                    fields = mapOf(
                        "ssid" to ssid,
                        "network" to network.networkHandle,
                        "attempt" to attempt,
                    ),
                )
                if (!onAvailable.isCompleted) onAvailable.complete(network)
            }
            override fun onUnavailable() {
                logWifiScanSnapshot(ssid = ssid, phase = "on_unavailable", attempt = attempt)
                InstaLog.log(
                    InstaLogCategory.WIFI,
                    level = InstaLogLevel.WARN,
                    event = "camera_ap_join_unavailable",
                    fields = mapOf("ssid" to ssid, "attempt" to attempt),
                )
                if (!onAvailable.isCompleted) {
                    onAvailable.completeExceptionally(
                        Insta360Error.HotspotApplyFailed("camera AP unavailable for SSID=$ssid")
                    )
                }
            }
        }
        InstaLog.log(
            InstaLogCategory.WIFI,
            event = "camera_ap_join_requested",
            fields = mapOf(
                "ssid" to ssid,
                "attempt" to attempt,
                "timeout_ms" to timeoutMs,
            ),
        )
        logWifiScanSnapshot(ssid = ssid, phase = "before_request", attempt = attempt)
        cm.requestNetwork(request, cb, timeoutMs.toInt())
        try {
            withTimeoutOrNull(
                timeoutMs +
                    Insta360WiFiReachabilityPolicy.joinAwaitSlackMs,
            ) { onAvailable.await() }
                ?: throw Insta360Error.HotspotApplyFailed(
                    "camera AP join timeout (${timeoutMs}ms)")
        } catch (t: CancellationException) {
            runCatching { cm.unregisterNetworkCallback(cb) }
            throw t
        } catch (t: Throwable) {
            runCatching { cm.unregisterNetworkCallback(cb) }
            if (t is Insta360Error) throw t
            throw Insta360Error.HotspotApplyFailed(t.message ?: "unknown")
        }
        return cb
    }

    @SuppressLint("MissingPermission")
    private fun logWifiScanSnapshot(ssid: String, phase: String, attempt: Int) {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return
        val results = runCatching {
            wifiManager.scanResults
                .asSequence()
                .mapNotNull { result ->
                    val resultSsid = result.SSID?.trim().orEmpty()
                    if (resultSsid.isBlank()) return@mapNotNull null
                    if (
                        resultSsid == ssid ||
                        resultSsid.contains("GO 3S", ignoreCase = true) ||
                        resultSsid.endsWith(".OSC", ignoreCase = true)
                    ) {
                        "${resultSsid}@${result.level}"
                    } else {
                        null
                    }
                }
                .distinct()
                .take(8)
                .toList()
        }.getOrElse { error ->
            InstaLog.log(
                InstaLogCategory.WIFI,
                level = InstaLogLevel.WARN,
                event = "wifi_scan_snapshot_failed",
                fields = mapOf(
                    "ssid" to ssid,
                    "phase" to phase,
                    "attempt" to attempt,
                    "error" to (error.message ?: error::class.java.simpleName),
                ),
            )
            return
        }
        InstaLog.log(
            InstaLogCategory.WIFI,
            event = "wifi_scan_snapshot",
            fields = mapOf(
                "ssid" to ssid,
                "phase" to phase,
                "attempt" to attempt,
                "visible_target" to results.any { it.startsWith("$ssid@") },
                "matches" to results,
            ),
        )
    }

    private suspend fun waitForReachability(cm: ConnectivityManager) {
        for ((index, delayMs) in Insta360WiFiReachabilityPolicy.probeDelaysMs.withIndex()) {
            if (probeOnce(cm)) return
            if (index < Insta360WiFiReachabilityPolicy.probeDelaysMs.lastIndex) {
                delay(delayMs)
            }
        }
        throw Insta360Error.CameraNotReachable
    }

    private fun probeOnce(cm: ConnectivityManager): Boolean {
        return runCatching {
            val url = URL("http://$cameraHost:$cameraPort/")
            val network = cm.boundNetworkForProcess
            ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).run {
                connectTimeout = 3_000
                readTimeout = 3_000
                requestMethod = "GET"
                connect()
                disconnect()
                true
            }
        }.getOrDefault(false)
    }

    private fun fetchResource(
        cm: ConnectivityManager,
        remoteFileURI: String,
        destination: File,
        progress: (Double) -> Unit,
    ): Long {
        destination.parentFile?.mkdirs()

        val url = URL("http://$cameraHost:$cameraPort${normalizedCameraFileURI(remoteFileURI)}")
        val network = cm.boundNetworkForProcess
        val conn = ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            requestMethod = "GET"
        }

        val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
        var written = 0L
        try {
            conn.connect()
            conn.inputStream.use { input ->
                destination.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            progress((written.toDouble() / total).coerceIn(0.0, 1.0))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            throw Insta360Error.DownloadFailed(t.message ?: "unknown")
        } finally {
            conn.disconnect()
        }
        return written
    }

    private suspend fun resolveRemoteDestinations(
        cm: ConnectivityManager,
        remoteFileURI: String,
        destination: File,
        sidecar: Insta360PendingSidecar?,
    ): List<Pair<String, File>> {
        val uris = resolveRemoteFileURIIfNeeded(remoteFileURI, sidecar)
        return uris.zip(destinationsFor(uris, destination))
    }

    private suspend fun resolveRemoteFileURIIfNeeded(
        remoteFileURI: String,
        sidecar: Insta360PendingSidecar?,
    ): List<String> {
        if (!Insta360PendingSidecar.needsCameraFileURIResolution(remoteFileURI)) {
            return listOf(normalizedCameraFileURI(remoteFileURI))
        }

        val uris = fetchCameraFileInfoList().map { it.fileUri }
        val start = sidecar?.bleAckWallClockMs
        val stop = sidecar?.stopWallClockMs
        if (start != null && stop != null) {
            val matched = Insta360PendingResolver.matchSegments(
                uris = uris,
                window = Insta360PendingResolver.Window(
                    startWallMs = start,
                    endWallMs = stop,
                    expectedDurationSec = sidecar.cameraDurationSec?.toInt(),
                    expectedSegments = sidecar.expectedSegments,
                ),
            )
            if (matched.isNotEmpty()) return matched
            throw Insta360Error.DownloadFailed(
                "no camera mp4 in expected window [$start..$stop] for ${sidecar.streamId}; camera has ${uris.size} files")
        }

        val resolved = Insta360VideoURIFallback.bestCandidate(uris)
            ?: throw Insta360Error.DownloadFailed(
                "could not resolve camera video URI after stopCapture returned no URI")
        return listOf(resolved)
    }

    private suspend fun fetchCameraFileInfoList(): List<Insta360FileInfo> {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                openCameraWifi()
                val manager = Insta360OneSDKBridge.manager
                val urls = (manager.getAllUrlListIncludeRecording() + manager.rawUrlListOrEmpty())
                    .mapNotNull(::normalizedCameraFileURIOrNull)
                    .filter(::isDownloadableCameraVideoURI)
                    .distinct()
                if (urls.isNotEmpty()) {
                    return urls
                        .map(::fileInfo)
                        .sortedWith(compareByDescending<Insta360FileInfo> { it.createdAtIso }
                            .thenBy { it.fileUri })
                }
                lastError = Insta360Error.DownloadFailed("camera file list returned empty")
            } catch (t: Throwable) {
                lastError = t
            } finally {
                closeCameraWifi()
            }
            if (attempt == 0) delay(500)
        }
        throw Insta360Error.DownloadFailed(
            "camera album listing failed (${lastError?.message ?: "unknown"})")
    }

    private suspend fun openCameraWifi() {
        val result = CompletableDeferred<Unit>()
        Insta360OneSDKBridge.manager.openCameraWifi(object : ICameraOperateCallback {
            override fun onSuccessful() {
                if (!result.isCompleted) result.complete(Unit)
            }

            override fun onFailed() {
                if (!result.isCompleted) {
                    result.completeExceptionally(
                        Insta360Error.DownloadFailed("openCameraWifi failed"))
                }
            }

            override fun onCameraConnectError() {
                if (!result.isCompleted) {
                    result.completeExceptionally(
                        Insta360Error.DownloadFailed("openCameraWifi camera connect error"))
                }
            }
        })
        withTimeoutOrNull(12_000) { result.await() }
            ?: throw Insta360Error.DownloadFailed("openCameraWifi timed out after 12s")
    }

    private fun closeCameraWifi() {
        runCatching {
            Insta360OneSDKBridge.manager.closeCameraWifi(object : ICameraOperateCallback {
                override fun onSuccessful() = Unit
                override fun onFailed() = Unit
                override fun onCameraConnectError() = Unit
            })
        }
    }

    private fun InstaCameraManager.rawUrlListOrEmpty(): List<String> =
        runCatching { getRawUrlList() }.getOrDefault(emptyList())

    private fun destinationsFor(uris: List<String>, destination: File): List<File> {
        if (uris.size <= 1) return listOf(destination)
        val parent = destination.parentFile ?: File(".")
        val stem = destination.nameWithoutExtension
        val ext = destination.extension.takeIf { it.isNotBlank() } ?: "mp4"
        return uris.indices.map { index ->
            File(parent, "%s_seg%02d.%s".format(stem, index + 1, ext))
        }
    }

    private fun normalizedCameraFileURI(raw: String): String =
        normalizedCameraFileURIOrNull(raw) ?: raw

    private fun normalizedCameraFileURIOrNull(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val url = URL(value)
            if (url.host == cameraHost && url.path.isNotBlank()) url.path else value
        }.getOrElse { value }
    }

    private fun fileInfo(uri: String): Insta360FileInfo {
        val createdAtIso = Insta360PendingResolver.parseFilenameTimestampMs(uri)
            ?.let { DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(it)) }
            ?: DateTimeFormatter.ISO_INSTANT.format(Instant.EPOCH)
        return Insta360FileInfo(
            fileUri = uri,
            createdAtIso = createdAtIso,
            durationSec = 0.0,
            sizeBytes = 0L,
        )
    }

    private fun isDownloadableCameraVideoURI(uri: String): Boolean {
        val lower = uri.lowercase()
        return (lower.endsWith(".mp4") || lower.endsWith(".insv")) && !lower.contains("lrv")
    }

    private suspend fun releaseCameraNetwork(
        cm: ConnectivityManager,
        callback: ConnectivityManager.NetworkCallback,
    ) {
        runCatching { cm.bindProcessToNetwork(null) }
        runCatching { cm.unregisterNetworkCallback(callback) }
        awaitNetworkRestore(cm, Insta360WiFiReachabilityPolicy.restoreTimeoutMs)
    }

    private suspend fun awaitNetworkRestore(cm: ConnectivityManager, maxWaitMs: Long) {
        if (hasInternet(cm, cm.activeNetwork)) return

        val restored = CompletableDeferred<Unit>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (hasInternet(cm, network) && !restored.isCompleted) restored.complete(Unit)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onFailure { return }
        try {
            withTimeoutOrNull(maxWaitMs) { restored.await() }
        } finally {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

    private fun hasInternet(cm: ConnectivityManager, network: Network?): Boolean {
        val caps = network?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
