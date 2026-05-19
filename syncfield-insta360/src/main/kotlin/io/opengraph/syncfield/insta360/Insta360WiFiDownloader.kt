package io.opengraph.syncfield.insta360

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.arashivision.insta360.basecamera.camera.BaseCamera
import com.arashivision.insta360.basecamera.camera.CameraManager
import com.arashivision.sdkcamera.camera.InstaCameraManager
import com.arashivision.sdkcamera.camera.callback.ICameraChangedCallback
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
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
 * Builds a WPA2-PSK [WifiConfiguration] for the API 28 legacy join
 * path. Exposed as an internal top-level function so the SSID/PSK
 * quoting and WPA_PSK key-management bits can be exercised without
 * standing up a full downloader on the JVM.
 *
 * Android's pre-Q supplicant requires both fields to be wrapped in
 * literal double quotes. Hidden SSIDs additionally need the
 * `hiddenSSID` flag — otherwise the supplicant won't probe for them.
 */
internal fun buildLegacyWifiConfiguration(
    ssid: String,
    passphrase: String,
    hiddenSsid: Boolean,
): WifiConfiguration = WifiConfiguration().apply {
    SSID = "\"$ssid\""
    preSharedKey = "\"$passphrase\""
    hiddenSSID = hiddenSsid
    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
    allowedProtocols.set(WifiConfiguration.Protocol.RSN)
    allowedProtocols.set(WifiConfiguration.Protocol.WPA)
    allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
    allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
}

/**
 * Compares the active [WifiInfo.getSsid] (which Android wraps in
 * literal double quotes on most OEMs) against the SSID we asked the
 * supplicant to join. Treats `<unknown ssid>` — the placeholder
 * returned before association completes or when location permission
 * is missing — as "no match".
 *
 * Pure-string logic so it's covered by a JVM unit test; the legacy
 * [ConnectivityManager.NetworkCallback] uses it to debounce false
 * positives where Android briefly hands us a [Network] for a
 * different transport (cellular dropped, captive portal, etc.).
 */
internal fun legacyConnectionMatchesTarget(
    rawConnectedSsid: String?,
    target: String,
): Boolean {
    val raw = rawConnectedSsid?.trim() ?: return false
    if (raw.isBlank()) return false
    val stripped = if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
        raw.substring(1, raw.length - 1)
    } else {
        raw
    }
    if (stripped.equals("<unknown ssid>", ignoreCase = true)) return false
    if (stripped.isBlank()) return false
    return stripped == target
}

/**
 * Switches the phone onto an Insta360 camera AP, downloads a clip over
 * the camera's media HTTP endpoint, and tears down the network request
 * afterwards so the device can reconnect to the user's home WiFi (or
 * fall through to cellular).
 *
 * Android's [ConnectivityManager.requestNetwork] + [WifiNetworkSpecifier]
 * is the API-29+ replacement for the deprecated
 * `WifiManager.enableNetwork`. The user is shown a system "use this
 * network?" dialog the first time we apply a specifier; this is a
 * platform constraint we can't bypass.
 *
 * Mirrors the iOS [Insta360WiFiDownloader.download] flow but uses the
 * Android NetworkRequest API. Reachability is verified against the
 * OneDriver control port (6666), while media files are fetched from
 * the camera HTTP server on port 80.
 */
class Insta360WiFiDownloader(private val context: Context) {

    private val defaultCameraHost = "192.168.42.1"
    private val cameraControlPort = 6666
    private val cameraHttpPort = 80

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

    private data class DownloadEndpoint(
        val host: String,
        val port: Int,
        val path: String,
        val sdkHttpPrefix: String,
        val transport: String,
    )

    /**
     * Tracks how we joined the camera AP so [releaseCameraNetwork] can
     * tear down the correct platform state. The Q+ branch only owns a
     * [ConnectivityManager.NetworkCallback]; the P branch additionally
     * owns a `WifiConfiguration` it added via [WifiManager.addNetwork]
     * and a snapshot of previously-enabled configs to re-enable on
     * teardown.
     */
    private sealed class CameraNetworkJoin {
        abstract val callback: ConnectivityManager.NetworkCallback

        data class Modern(
            override val callback: ConnectivityManager.NetworkCallback,
        ) : CameraNetworkJoin()

        data class Legacy(
            override val callback: ConnectivityManager.NetworkCallback,
            val addedNetId: Int,
            val previouslyEnabledNetIds: List<Int>,
        ) : CameraNetworkJoin()
    }

    /**
     * Atomically: join camera AP -> probe reachability -> fetch clip ->
     * release network request.
     *
     * Returns the size in bytes of the downloaded file.
     */
    suspend fun download(
        remoteFileURI: String,
        destination: File,
        ssid: String,
        passphrase: String,
        sidecar: Insta360PendingSidecar? = null,
        progress: (Double) -> Unit,
    ): Long {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val join = applyCameraNetwork(cm, ssid, passphrase)
        try {
            val cameraHost = waitForReachability(cm)
            var written = 0L
            for ((uri, dst) in resolveRemoteDestinations(
                remoteFileURI = remoteFileURI,
                destination = destination,
                sidecar = sidecar,
            )) {
                written += fetchResource(cm, cameraHost, uri, dst, progress)
            }
            return written
        } finally {
            releaseCameraNetwork(cm, join)
        }
    }

    suspend fun downloadBatch(
        ssid: String,
        passphrase: String,
        items: List<BatchItem>,
        onItemStart: (BatchItem) -> Unit,
        progress: (BatchItem, Double) -> Unit,
    ): List<BatchResult> {
        if (items.isEmpty()) return emptyList()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val join = applyCameraNetwork(cm, ssid, passphrase)
        return try {
            val cameraHost = waitForReachability(cm)
            items.map { item ->
                runCatching {
                    onItemStart(item)
                    for ((uri, dst) in resolveRemoteDestinations(
                        remoteFileURI = item.remoteFileURI,
                        destination = item.destination,
                        sidecar = item.sidecar,
                    )) {
                        fetchResource(
                            cm = cm,
                            cameraHost = cameraHost,
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
            releaseCameraNetwork(cm, join)
        }
    }

    suspend fun listFiles(
        ssid: String,
        passphrase: String,
    ): List<Insta360FileInfo> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val join = applyCameraNetwork(cm, ssid, passphrase)
        return try {
            waitForReachability(cm)
            fetchCameraFileInfoList()
        } finally {
            closeSdkWifiCamera()
            releaseCameraNetwork(cm, join)
        }
    }

    /**
     * SDK_INT dispatcher. Q+ uses the app-scoped
     * [WifiNetworkSpecifier] path via [applyNetworkSuggestion]; P falls
     * back to the system-scoped [WifiManager.addNetwork] +
     * [WifiManager.enableNetwork] flow via
     * [applyNetworkSuggestionLegacy]. Both branches share the
     * [waitForReachability] / [fetchResource] downstream pipeline.
     */
    private suspend fun applyCameraNetwork(
        cm: ConnectivityManager,
        ssid: String,
        passphrase: String,
    ): CameraNetworkJoin {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            CameraNetworkJoin.Modern(applyNetworkSuggestion(cm, ssid, passphrase))
        } else {
            applyNetworkSuggestionLegacy(cm, ssid, passphrase)
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
        val visibleTarget = logWifiScanSnapshot(
            ssid = ssid,
            phase = "before_request",
            attempt = attempt,
            hiddenSsid = false,
        )
        val hiddenSsid = !visibleTarget
        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
        if (hiddenSsid) {
            specifierBuilder.setIsHiddenSsid(true)
        }
        val specifier = specifierBuilder.build()
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
                logNetworkSnapshot(
                    cm = cm,
                    network = network,
                    ssid = ssid,
                    attempt = attempt,
                    event = "camera_ap_join_available_network",
                )
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
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                logNetworkSnapshot(
                    cm = cm,
                    network = network,
                    ssid = ssid,
                    attempt = attempt,
                    event = "camera_ap_link_properties",
                    linkProperties = linkProperties,
                )
            }
            override fun onUnavailable() {
                logWifiScanSnapshot(
                    ssid = ssid,
                    phase = "on_unavailable",
                    attempt = attempt,
                    hiddenSsid = hiddenSsid,
                )
                InstaLog.log(
                    InstaLogCategory.WIFI,
                    level = InstaLogLevel.WARN,
                    event = "camera_ap_join_unavailable",
                    fields = mapOf(
                        "ssid" to ssid,
                        "attempt" to attempt,
                        "hidden_ssid" to hiddenSsid,
                    ),
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
                "hidden_ssid" to hiddenSsid,
            ),
        )
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

    /**
     * Legacy (API 28) join flow. Mirrors [applyNetworkSuggestion]'s
     * deadline + retry shape but uses the pre-Q
     * [WifiManager.addNetwork] / [WifiManager.enableNetwork] +
     * [ConnectivityManager.registerNetworkCallback] approach.
     *
     * UX caveats vs. the Q+ path (system-scoped, not app-scoped):
     * - Briefly disconnects every app on the device from the user's
     *   home Wi-Fi while we own the radio.
     * - On teardown we re-enable previously-saved configs and call
     *   `reconnect()`; the supplicant then picks the highest-priority
     *   saved network. There is no platform guarantee the user's prior
     *   network comes back immediately.
     */
    @SuppressLint("MissingPermission")
    private suspend fun applyNetworkSuggestionLegacy(
        cm: ConnectivityManager,
        ssid: String,
        passphrase: String,
    ): CameraNetworkJoin.Legacy {
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
                return requestCameraNetworkLegacyOnce(
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
                    event = "camera_ap_join_retry_legacy",
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
            "camera AP unavailable for SSID=$ssid after ${Insta360WiFiReachabilityPolicy.joinTimeoutMs}ms [legacy]" +
                (lastError?.message?.let { " ($it)" } ?: "")
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCameraNetworkLegacyOnce(
        cm: ConnectivityManager,
        ssid: String,
        passphrase: String,
        attempt: Int,
        timeoutMs: Long,
    ): CameraNetworkJoin.Legacy {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw Insta360Error.HotspotApplyFailed("WIFI_SERVICE unavailable [legacy]")

        val visibleTarget = logWifiScanSnapshot(
            ssid = ssid,
            phase = "before_request_legacy",
            attempt = attempt,
            hiddenSsid = false,
        )
        val hiddenSsid = !visibleTarget

        val config = buildLegacyWifiConfiguration(ssid, passphrase, hiddenSsid)
        val previouslyEnabledNetIds = runCatching {
            wifiManager.configuredNetworks
                ?.asSequence()
                ?.filter { it.status != WifiConfiguration.Status.DISABLED }
                ?.map { it.networkId }
                ?.filter { it >= 0 }
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())

        val addedNetId = runCatching { wifiManager.addNetwork(config) }
            .getOrDefault(-1)
        if (addedNetId < 0) {
            throw Insta360Error.HotspotApplyFailed(
                "addNetwork returned $addedNetId for SSID=$ssid [legacy]"
            )
        }
        InstaLog.log(
            InstaLogCategory.WIFI,
            event = "camera_ap_join_requested_legacy",
            fields = mapOf(
                "ssid" to ssid,
                "attempt" to attempt,
                "timeout_ms" to timeoutMs,
                "hidden_ssid" to hiddenSsid,
                "net_id" to addedNetId,
                "previously_enabled_count" to previouslyEnabledNetIds.size,
            ),
        )

        val enableOk = runCatching {
            wifiManager.enableNetwork(addedNetId, /*disableOthers=*/true)
        }.getOrDefault(false)
        if (!enableOk) {
            runCatching { wifiManager.removeNetwork(addedNetId) }
            throw Insta360Error.HotspotApplyFailed(
                "enableNetwork returned false for netId=$addedNetId SSID=$ssid [legacy]"
            )
        }
        runCatching { wifiManager.reconnect() }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val onAvailable = CompletableDeferred<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val connected = runCatching { wifiManager.connectionInfo }.getOrNull()
                if (!legacyConnectionMatchesTarget(connected?.ssid, ssid)) {
                    InstaLog.log(
                        InstaLogCategory.WIFI,
                        level = InstaLogLevel.DEBUG,
                        event = "camera_ap_join_available_ignored_legacy",
                        fields = mapOf(
                            "target_ssid" to ssid,
                            "connected_ssid" to (connected?.ssid ?: ""),
                            "attempt" to attempt,
                        ),
                    )
                    return
                }
                cm.bindProcessToNetwork(network)
                runCatching { Insta360OneSDKBridge.bindNetwork(network) }
                logNetworkSnapshot(
                    cm = cm,
                    network = network,
                    ssid = ssid,
                    attempt = attempt,
                    event = "camera_ap_join_available_network_legacy",
                )
                InstaLog.log(
                    InstaLogCategory.WIFI,
                    event = "camera_ap_join_available_legacy",
                    fields = mapOf(
                        "ssid" to ssid,
                        "network" to network.networkHandle,
                        "attempt" to attempt,
                    ),
                )
                if (!onAvailable.isCompleted) onAvailable.complete(network)
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                logNetworkSnapshot(
                    cm = cm,
                    network = network,
                    ssid = ssid,
                    attempt = attempt,
                    event = "camera_ap_link_properties_legacy",
                    linkProperties = linkProperties,
                )
            }
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onFailure { error ->
                runCatching { wifiManager.removeNetwork(addedNetId) }
                throw Insta360Error.HotspotApplyFailed(
                    "registerNetworkCallback failed [legacy]: ${error.message}"
                )
            }
        try {
            withTimeoutOrNull(
                timeoutMs + Insta360WiFiReachabilityPolicy.joinAwaitSlackMs,
            ) { onAvailable.await() }
                ?: throw Insta360Error.HotspotApplyFailed(
                    "camera AP join timeout (${timeoutMs}ms) [legacy]"
                )
        } catch (t: CancellationException) {
            runCatching { cm.unregisterNetworkCallback(cb) }
            runCatching { wifiManager.removeNetwork(addedNetId) }
            throw t
        } catch (t: Throwable) {
            runCatching { cm.unregisterNetworkCallback(cb) }
            runCatching { wifiManager.removeNetwork(addedNetId) }
            if (t is Insta360Error) throw t
            throw Insta360Error.HotspotApplyFailed(t.message ?: "unknown [legacy]")
        }
        return CameraNetworkJoin.Legacy(
            callback = cb,
            addedNetId = addedNetId,
            previouslyEnabledNetIds = previouslyEnabledNetIds,
        )
    }

    @SuppressLint("MissingPermission")
    private fun logWifiScanSnapshot(
        ssid: String,
        phase: String,
        attempt: Int,
        hiddenSsid: Boolean,
    ): Boolean {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
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
                    "hidden_ssid" to hiddenSsid,
                    "error" to (error.message ?: error::class.java.simpleName),
                ),
            )
            return false
        }
        val visibleTarget = results.any { it.startsWith("$ssid@") }
        InstaLog.log(
            InstaLogCategory.WIFI,
            event = "wifi_scan_snapshot",
            fields = mapOf(
                "ssid" to ssid,
                "phase" to phase,
                "attempt" to attempt,
                "hidden_ssid" to hiddenSsid,
                "visible_target" to visibleTarget,
                "matches" to results,
            ),
        )
        return visibleTarget
    }

    private suspend fun waitForReachability(cm: ConnectivityManager): String {
        var lastCandidates = emptyList<String>()
        for ((index, delayMs) in Insta360WiFiReachabilityPolicy.probeDelaysMs.withIndex()) {
            val candidates = cameraHostCandidates(cm)
            lastCandidates = candidates
            for (host in candidates) {
                if (probeOnce(cm, host, index + 1)) return host
            }
            if (index < Insta360WiFiReachabilityPolicy.probeDelaysMs.lastIndex) {
                delay(delayMs)
            }
        }
        throw Insta360Error.DownloadFailed(
            "camera AP reachable timeout; tried ${lastCandidates.joinToString(",")}"
        )
    }

    private fun probeOnce(cm: ConnectivityManager, host: String, attempt: Int): Boolean {
        return runCatching {
            val network = cm.boundNetworkForProcess
            val socket = if (network != null) {
                network.socketFactory.createSocket()
            } else {
                Socket()
            }
            socket.use {
                it.connect(InetSocketAddress(host, cameraControlPort), 3_000)
            }
            InstaLog.log(
                InstaLogCategory.WIFI,
                event = "camera_ap_probe_ok",
                fields = mapOf(
                    "host" to host,
                    "port" to cameraControlPort,
                    "attempt" to attempt,
                    "network" to (network?.networkHandle ?: -1L),
                ),
            )
            true
        }.getOrElse { error ->
            InstaLog.log(
                InstaLogCategory.WIFI,
                level = InstaLogLevel.DEBUG,
                event = "camera_ap_probe_failed",
                fields = mapOf(
                    "host" to host,
                    "port" to cameraControlPort,
                    "attempt" to attempt,
                    "error" to (error.message ?: error::class.java.simpleName),
                ),
            )
            false
        }
    }

    private fun cameraHostCandidates(cm: ConnectivityManager): List<String> {
        val network = cm.boundNetworkForProcess ?: cm.activeNetwork
        val linkProperties = network?.let { cm.getLinkProperties(it) }
        val gateways = linkProperties
            ?.routes
            ?.asSequence()
            ?.mapNotNull { it.gateway as? Inet4Address }
            ?.mapNotNull { it.hostAddress }
            ?.filter { it.isNotBlank() && it != "0.0.0.0" }
            ?.toList()
            .orEmpty()
        return (gateways + defaultCameraHost).distinct()
    }

    private fun logNetworkSnapshot(
        cm: ConnectivityManager,
        network: Network,
        ssid: String,
        attempt: Int,
        event: String,
        linkProperties: LinkProperties? = cm.getLinkProperties(network),
    ) {
        val caps = cm.getNetworkCapabilities(network)
        val addresses = linkProperties
            ?.linkAddresses
            ?.map { "${it.address.hostAddress}/${it.prefixLength}" }
            .orEmpty()
        val routes = linkProperties
            ?.routes
            ?.map { route ->
                val gateway = route.gateway?.hostAddress ?: ""
                "${route.destination}->${gateway}"
            }
            .orEmpty()
        val dns = linkProperties
            ?.dnsServers
            ?.mapNotNull { it.hostAddress }
            .orEmpty()
        InstaLog.log(
            InstaLogCategory.WIFI,
            event = event,
            fields = mapOf(
                "ssid" to ssid,
                "attempt" to attempt,
                "network" to network.networkHandle,
                "interface" to (linkProperties?.interfaceName ?: ""),
                "addresses" to addresses,
                "routes" to routes,
                "dns" to dns,
                "host_candidates" to cameraHostCandidates(cm),
                "has_internet" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false),
                "validated" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false),
                "captive_portal" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ?: false),
            ),
        )
    }

    private suspend fun fetchResource(
        cm: ConnectivityManager,
        cameraHost: String,
        remoteFileURI: String,
        destination: File,
        progress: (Double) -> Unit,
    ): Long {
        destination.parentFile?.mkdirs()

        val cameraPath = normalizedCameraFileURI(remoteFileURI)
        var socket: Socket? = null
        var endpoint = DownloadEndpoint(
            host = cameraHost,
            port = cameraHttpPort,
            path = cameraPath,
            sdkHttpPrefix = "",
            transport = "camera_http",
        )

        var written = 0L
        try {
            endpoint = resolveDownloadEndpoint(cameraHost, cameraPath)
            val network = cm.boundNetworkForProcess
            socket = network?.socketFactory?.createSocket() ?: Socket()
            InstaLog.log(
                InstaLogCategory.WIFI,
                event = "download_fetch_requested",
                fields = mapOf(
                    "host" to endpoint.host,
                    "port" to endpoint.port,
                    "path" to endpoint.path,
                    "network" to (network?.networkHandle ?: -1L),
                    "destination" to destination.absolutePath,
                    "transport" to endpoint.transport,
                    "sdk_http_prefix" to endpoint.sdkHttpPrefix,
                ),
            )
            socket.soTimeout = 30_000
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 10_000)

            val request = buildString {
                append("GET ").append(endpoint.path).append(" HTTP/1.1\r\n")
                append("Host: ").append(endpoint.host).append(':').append(endpoint.port).append("\r\n")
                append("Accept: */*\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            val socketOut = socket.getOutputStream()
            socketOut.write(request.toByteArray(StandardCharsets.US_ASCII))
            socketOut.flush()

            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val statusLine = readHttpLine(input)
            val code = parseHttpStatusCode(statusLine)
            val headers = readHttpHeaders(input)
            val total = headers["content-length"]?.toLongOrNull()?.takeIf { it > 0 } ?: -1L
            val chunked = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
            InstaLog.log(
                InstaLogCategory.WIFI,
                event = "download_fetch_response",
                fields = mapOf(
                    "host" to endpoint.host,
                    "path" to endpoint.path,
                    "status" to code,
                    "content_length" to total,
                    "chunked" to chunked,
                    "transport" to endpoint.transport,
                ),
            )
            if (code !in 200..299) {
                val body = readBodyPreview(input, chunked)
                throw Insta360Error.DownloadFailed(
                    "camera HTTP $code for ${endpoint.path}${if (body.isBlank()) "" else ": $body"}"
                )
            }

            destination.outputStream().use { out ->
                written = if (chunked) {
                    copyChunkedHttpBody(input, out)
                } else {
                    copyHttpBody(input, out, total, progress)
                }
                progress(1.0)
            }
        } catch (t: Throwable) {
            runCatching { destination.delete() }
            InstaLog.log(
                InstaLogCategory.WIFI,
                level = InstaLogLevel.WARN,
                event = "download_fetch_failed",
                fields = mapOf(
                    "host" to endpoint.host,
                    "port" to endpoint.port,
                    "path" to endpoint.path,
                    "error" to (t.message ?: t::class.java.simpleName),
                    "transport" to endpoint.transport,
                ),
            )
            throw Insta360Error.DownloadFailed(t.message ?: "unknown")
        } finally {
            runCatching { socket?.close() }
        }
        return written
    }

    private fun resolveDownloadEndpoint(cameraHost: String, cameraPath: String): DownloadEndpoint {
        val sdkHttpPrefix = runCatching {
            Insta360OneSDKBridge.manager.getCameraHttpPrefix().trim()
        }.getOrDefault("")
        if (sdkHttpPrefix.isBlank()) {
            return DownloadEndpoint(
                host = cameraHost,
                port = cameraHttpPort,
                path = cameraPath,
                sdkHttpPrefix = sdkHttpPrefix,
                transport = "camera_http",
            )
        }

        val candidate = if (
            sdkHttpPrefix.startsWith("http://", ignoreCase = true) ||
            sdkHttpPrefix.startsWith("https://", ignoreCase = true)
        ) {
            sdkHttpPrefix
        } else {
            "http://$sdkHttpPrefix"
        }
        return runCatching {
            val url = URL(candidate)
            DownloadEndpoint(
                host = url.host.takeIf { it.isNotBlank() } ?: cameraHost,
                port = when {
                    url.port > 0 -> url.port
                    url.defaultPort > 0 -> url.defaultPort
                    else -> cameraHttpPort
                },
                path = cameraPath,
                sdkHttpPrefix = sdkHttpPrefix,
                transport = "sdk_http_prefix",
            )
        }.getOrElse { error ->
            InstaLog.log(
                InstaLogCategory.WIFI,
                level = InstaLogLevel.WARN,
                event = "sdk_http_prefix_parse_failed",
                fields = mapOf(
                    "sdk_http_prefix" to sdkHttpPrefix,
                    "error" to (error.message ?: error::class.java.simpleName),
                ),
            )
            DownloadEndpoint(
                host = cameraHost,
                port = cameraHttpPort,
                path = cameraPath,
                sdkHttpPrefix = sdkHttpPrefix,
                transport = "camera_http",
            )
        }
    }

    private fun readHttpHeaders(input: BufferedInputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readHttpLine(input)
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }
    }

    private fun readHttpLine(input: BufferedInputStream, maxBytes: Int = 16 * 1024): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (buffer.size() == 0) {
                    throw Insta360Error.DownloadFailed("camera HTTP response ended before headers")
                }
                break
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
            if (buffer.size() > maxBytes) {
                throw Insta360Error.DownloadFailed("camera HTTP header too large")
            }
        }
        return buffer.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun parseHttpStatusCode(statusLine: String): Int {
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/", ignoreCase = true)) {
            throw Insta360Error.DownloadFailed("camera returned invalid HTTP status: $statusLine")
        }
        return parts[1].toIntOrNull()
            ?: throw Insta360Error.DownloadFailed("camera returned invalid HTTP status: $statusLine")
    }

    private fun copyHttpBody(
        input: InputStream,
        output: OutputStream,
        contentLength: Long,
        progress: (Double) -> Unit,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        if (contentLength > 0) {
            var remaining = contentLength
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) {
                    throw Insta360Error.DownloadFailed(
                        "camera HTTP body ended early ($written/$contentLength bytes)"
                    )
                }
                output.write(buffer, 0, n)
                written += n
                remaining -= n
                progress((written.toDouble() / contentLength).coerceIn(0.0, 1.0))
            }
            return written
        }

        while (true) {
            val n = input.read(buffer)
            if (n < 0) return written
            output.write(buffer, 0, n)
            written += n
        }
    }

    private fun copyChunkedHttpBody(input: BufferedInputStream, output: OutputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val sizeLine = readHttpLine(input).substringBefore(';').trim()
            val chunkSize = sizeLine.toIntOrNull(radix = 16)
                ?: throw Insta360Error.DownloadFailed("camera returned invalid chunk size: $sizeLine")
            if (chunkSize == 0) {
                while (readHttpLine(input).isNotEmpty()) {
                    // Drain trailers.
                }
                return written
            }

            var remaining = chunkSize
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (n < 0) {
                    throw Insta360Error.DownloadFailed("camera chunk ended early")
                }
                output.write(buffer, 0, n)
                written += n
                remaining -= n
            }
            readChunkTerminator(input)
        }
    }

    private fun readChunkTerminator(input: InputStream) {
        val first = input.read()
        if (first == '\n'.code) return
        if (first == '\r'.code) {
            val second = input.read()
            if (second == '\n'.code) return
        }
        throw Insta360Error.DownloadFailed("camera returned invalid chunk terminator")
    }

    private fun readBodyPreview(input: BufferedInputStream, chunked: Boolean): String {
        return runCatching {
            val buffer = ByteArray(512)
            val size = if (chunked) {
                val sizeLine = readHttpLine(input).substringBefore(';').trim()
                minOf(sizeLine.toIntOrNull(radix = 16) ?: 0, buffer.size)
            } else {
                buffer.size
            }
            val n = if (size > 0) input.read(buffer, 0, size) else -1
            if (n > 0) String(buffer, 0, n, StandardCharsets.UTF_8) else ""
        }.getOrDefault("")
    }

    private suspend fun resolveRemoteDestinations(
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
                ensureSdkWifiCameraOpen()
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
                closeSdkWifiCamera()
            }
            if (attempt == 0) delay(500)
        }
        throw Insta360Error.DownloadFailed(
            "camera album listing failed (${lastError?.message ?: "unknown"})")
    }

    private suspend fun ensureSdkWifiCameraOpen(timeoutMs: Long = 8_000L) {
        val manager = Insta360OneSDKBridge.manager
        if (manager.cameraConnectedType == InstaCameraManager.CONNECT_TYPE_WIFI) {
            InstaLog.log(InstaLogCategory.WIFI, event = "sdk_wifi_camera_already_open")
            return
        }

        val opened = CompletableDeferred<Unit>()
        val listener = object : ICameraChangedCallback {
            override fun onCameraStatusChanged(enabled: Boolean, connectType: Int) {
                if (
                    enabled &&
                    connectType == InstaCameraManager.CONNECT_TYPE_WIFI &&
                    !opened.isCompleted
                ) {
                    opened.complete(Unit)
                }
            }

            override fun onCameraConnectError(errorCode: Int) {
                if (!opened.isCompleted) {
                    opened.completeExceptionally(
                        Insta360Error.DownloadFailed("SDK WiFi camera connect error code=$errorCode")
                    )
                }
            }
        }
        manager.registerCameraChangedCallback(listener)
        try {
            InstaLog.log(
                InstaLogCategory.WIFI,
                event = "sdk_wifi_camera_open_requested",
                fields = mapOf("timeout_ms" to timeoutMs),
            )
            manager.openCamera(InstaCameraManager.CONNECT_TYPE_WIFI)
            val connected = withTimeoutOrNull(timeoutMs) { opened.await() }
            if (connected == null &&
                manager.cameraConnectedType != InstaCameraManager.CONNECT_TYPE_WIFI
            ) {
                throw Insta360Error.DownloadFailed("SDK WiFi camera open timed out after ${timeoutMs}ms")
            }
            InstaLog.log(InstaLogCategory.WIFI, event = "sdk_wifi_camera_open_ok")
        } finally {
            runCatching { manager.unregisterCameraChangedCallback(listener) }
        }
    }

    private fun closeSdkWifiCamera() {
        runCatching {
            if (Insta360OneSDKBridge.manager.cameraConnectedType == InstaCameraManager.CONNECT_TYPE_WIFI) {
                Insta360OneSDKBridge.manager.closeCamera()
                InstaLog.log(InstaLogCategory.WIFI, event = "sdk_wifi_camera_closed")
            }
        }
    }

    private fun setCameraFileAccessState(state: Int) {
        val camera = runCatching {
            CameraManager.getInstance().getReadyCameraByConnectType(BaseCamera.ConnectType.WIFI)
                ?: CameraManager.getInstance().getCameraByConnectType(BaseCamera.ConnectType.WIFI)
        }.getOrNull()
        if (camera == null) {
            InstaLog.log(
                InstaLogCategory.WIFI,
                level = InstaLogLevel.WARN,
                event = "camera_file_access_state_skipped",
                fields = mapOf("state" to state),
            )
            return
        }

        runCatching {
            camera.setAccessCameraFileState(state)
            InstaLog.log(
                InstaLogCategory.WIFI,
                event = "camera_file_access_state_set",
                fields = mapOf("state" to state),
            )
        }.getOrElse { error ->
            InstaLog.log(
                InstaLogCategory.WIFI,
                level = InstaLogLevel.WARN,
                event = "camera_file_access_state_failed",
                fields = mapOf(
                    "state" to state,
                    "error" to (error.message ?: error::class.java.simpleName),
                ),
            )
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
            if (url.path.isNotBlank()) url.path else value
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
        join: CameraNetworkJoin,
    ) {
        closeSdkWifiCamera()
        runCatching { cm.bindProcessToNetwork(null) }
        runCatching { cm.unregisterNetworkCallback(join.callback) }
        if (join is CameraNetworkJoin.Legacy) {
            releaseLegacyWifiConfiguration(join)
        }
        awaitNetworkRestore(cm, Insta360WiFiReachabilityPolicy.restoreTimeoutMs)
    }

    /**
     * Removes the [WifiConfiguration] we added in
     * [applyNetworkSuggestionLegacy] and best-effort re-enables the
     * caller's previously-saved networks so the supplicant can
     * reconnect to the user's home Wi-Fi. Every step is wrapped in
     * [runCatching] because OEM Wi-Fi stacks on P frequently return
     * `false` for transient reasons; failures are observable through
     * `wifi_legacy_release_*` log events.
     */
    @SuppressLint("MissingPermission")
    private fun releaseLegacyWifiConfiguration(join: CameraNetworkJoin.Legacy) {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            InstaLog.log(
                InstaLogCategory.WIFI,
                level = InstaLogLevel.WARN,
                event = "wifi_legacy_release_no_manager",
                fields = mapOf("net_id" to join.addedNetId),
            )
            return
        }
        val removed = runCatching { wifiManager.removeNetwork(join.addedNetId) }
            .getOrElse { error ->
                InstaLog.log(
                    InstaLogCategory.WIFI,
                    level = InstaLogLevel.WARN,
                    event = "wifi_legacy_release_remove_failed",
                    fields = mapOf(
                        "net_id" to join.addedNetId,
                        "error" to (error.message ?: error::class.java.simpleName),
                    ),
                )
                false
            }
        val restored = mutableListOf<Int>()
        for (netId in join.previouslyEnabledNetIds) {
            val ok = runCatching { wifiManager.enableNetwork(netId, /*disableOthers=*/false) }
                .getOrDefault(false)
            if (ok) restored += netId
        }
        runCatching { wifiManager.reconnect() }
        InstaLog.log(
            InstaLogCategory.WIFI,
            event = "wifi_legacy_release_done",
            fields = mapOf(
                "net_id" to join.addedNetId,
                "removed" to removed,
                "restored_net_ids" to restored,
                "previously_enabled_count" to join.previouslyEnabledNetIds.size,
            ),
        )
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
