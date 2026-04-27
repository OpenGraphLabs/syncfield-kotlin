package io.opengraph.syncfield.insta360

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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

    /**
     * Atomically: join camera AP → probe reachability → fetch clip →
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
        progress: (Double) -> Unit,
    ): Long {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = applyNetworkSuggestion(cm, ssid, passphrase)
        try {
            waitForReachability(cm)
            return fetchResource(cm, remoteFileURI, destination, progress)
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun applyNetworkSuggestion(
        cm: ConnectivityManager,
        ssid: String,
        passphrase: String,
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
                if (!onAvailable.isCompleted) onAvailable.complete(network)
            }
            override fun onUnavailable() {
                if (!onAvailable.isCompleted) {
                    onAvailable.completeExceptionally(
                        Insta360Error.HotspotApplyFailed("system rejected SSID=$ssid")
                    )
                }
            }
        }
        cm.requestNetwork(request, cb)
        try {
            withTimeoutOrNull(20_000) { onAvailable.await() }
                ?: throw Insta360Error.HotspotApplyFailed("camera AP join timeout (20s)")
        } catch (t: TimeoutCancellationException) {
            throw Insta360Error.HotspotApplyFailed("camera AP join timeout (20s)")
        } catch (t: Throwable) {
            if (t is Insta360Error) throw t
            throw Insta360Error.HotspotApplyFailed(t.message ?: "unknown")
        }
        return cb
    }

    private suspend fun waitForReachability(cm: ConnectivityManager) {
        repeat(3) {
            if (probeOnce()) return
            delay(1_000)
        }
        throw Insta360Error.CameraNotReachable
    }

    private suspend fun probeOnce(): Boolean {
        return runCatching {
            val url = URL("http://$cameraHost:$cameraPort/")
            (url.openConnection() as HttpURLConnection).run {
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

        val url = URL("http://$cameraHost:$cameraPort$remoteFileURI")
        val conn = (url.openConnection() as HttpURLConnection).apply {
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
}
