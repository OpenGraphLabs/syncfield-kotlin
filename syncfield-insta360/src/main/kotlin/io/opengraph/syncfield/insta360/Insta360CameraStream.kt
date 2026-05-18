package io.opengraph.syncfield.insta360

import android.content.Context
import io.opengraph.syncfield.HealthBus
import io.opengraph.syncfield.HealthEvent
import io.opengraph.syncfield.SessionClock
import io.opengraph.syncfield.StreamCapabilities
import io.opengraph.syncfield.StreamConnectContext
import io.opengraph.syncfield.StreamIngestReport
import io.opengraph.syncfield.StreamStopReport
import io.opengraph.syncfield.SyncFieldStream
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import io.opengraph.syncfield.writers.WriterFactory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import java.io.File

/**
 * BLE-triggered Insta360 Go 3S camera stream - Android counterpart of
 * `Insta360CameraStream.swift`.
 *
 * Lifecycle:
 * 1. [prepare] - assert SDK availability.
 * 2. [connect] - BLE pair with the camera.
 * 3. [startRecording] - BLE start-capture; record ACK host time.
 * 4. [stopRecording] - BLE stop-capture; the SDK returns the file URI.
 * 5. [ingest] - fetch WiFi creds over BLE, switch the phone onto the
 *    camera AP, download the mp4, restore the previous network, write
 *    `<streamId>.anchor.json` with the BLE-ACK anchor.
 * 6. [disconnect] - BLE unpair.
 */
class Insta360CameraStream(
    private val context: Context,
    override val streamId: String,
    private val controller: Insta360BLEController? = null,
    private val externallyConnected: Boolean = false,
) : SyncFieldStream {

    override val capabilities = StreamCapabilities(
        requiresIngest = true,
        producesFile = true,
        supportsPreciseTimestamps = true,
        providesAudioTrack = true,
    )

    private val ble = controller ?: Insta360BLEController(context)
    private val wifi = Insta360WiFiDownloader(context)
    private var adoptedHealthHandler: ((HealthEvent) -> Unit)? = null

    private var healthBus: HealthBus? = null
    /** Host-monotonic nanoseconds at the moment the BLE start-capture ACK was received. */
    private var bleAckMonotonicNs: Long = 0L
    private var bleAckWallClockMs: Long? = null
    private var cameraFileURI: String? = null
    private var currentEpisodeDirectory: File? = null

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    override suspend fun prepare() {
        if (!Insta360OneSDKBridge.available) throw Insta360Error.FrameworkNotLinked
    }

    override suspend fun connect(context: StreamConnectContext) {
        healthBus = context.healthBus
        if (!externallyConnected) {
            ble.pair()
        }
        publishHealth(HealthEvent.StreamConnected(streamId))
    }

    override suspend fun startRecording(clock: SessionClock, writerFactory: WriterFactory) {
        currentEpisodeDirectory = writerFactory.videoFile(streamId).parentFile
        bleAckMonotonicNs = ble.startRemoteRecording(clock)
        bleAckWallClockMs = System.currentTimeMillis()
        val episodeDir = currentEpisodeDirectory
        if (episodeDir != null) {
            runCatching {
                Insta360PendingSidecar.writeTentative(
                    episodeDir = episodeDir,
                    streamId = streamId,
                    role = roleFromStreamId(streamId),
                    deviceUuid = resolvedDeviceUuid(),
                    deviceName = resolvedDeviceName(),
                    bleAckNs = bleAckMonotonicNs,
                )
            }
        }
    }

    override suspend fun stopRecording(): StreamStopReport {
        val stopResult = ble.stopRemoteRecordingReliably()
        cameraFileURI = stopResult.cameraFileURI ?: Insta360PendingSidecar.unresolvedCameraFileURI
        val episodeDir = currentEpisodeDirectory
        val uri = cameraFileURI
        if (episodeDir != null && uri != null) {
            runCatching {
                Insta360PendingSidecar.write(
                    episodeDir = episodeDir,
                    streamId = streamId,
                    cameraFileURI = uri,
                    bleUuid = resolvedDeviceUuid(),
                    bleName = resolvedDeviceName(),
                    role = roleFromStreamId(streamId),
                    bleAckNs = bleAckMonotonicNs,
                    stopFailureReason = stopResult.diagnostic,
                    bleAckWallClockMs = bleAckWallClockMs,
                    stopWallClockMs = stopResult.stopWallClockMs,
                    expectedSegments = 1,
                )
            }
        }
        return StreamStopReport(streamId, frameCount = 0, kind = "video")
    }

    override suspend fun ingest(
        episodeDirectory: File,
        progress: (Double) -> Unit,
    ): StreamIngestReport {
        val uri = cameraFileURI ?: throw Insta360Error.DownloadFailed(
            "no camera file uri recorded from stopRecording")

        runCatching { ble.enableWiFiForDownload() }
            .onFailure { t ->
                InstaLog.log(
                    InstaLogCategory.WIFI,
                    level = InstaLogLevel.WARN,
                    event = "stream_wifi_enable_for_download_failed_continue",
                    fields = mapOf(
                        "stream_id" to streamId,
                        "error" to (t.message ?: t::class.java.simpleName),
                    ),
                )
            }
        val (ssid, passphrase) = ble.wifiCredentials()
        val destination = File(episodeDirectory, "$streamId.mp4")
        val sidecar = Insta360PendingSidecar.scan(episodeDirectory)
            .firstOrNull { it.streamId == streamId }
        wifi.download(
            remoteFileURI = uri,
            destination = destination,
            ssid = ssid,
            passphrase = passphrase,
            sidecar = sidecar,
            progress = progress,
        )

        // Persist BLE-ACK anchor sidecar so downstream alignment can map
        // the camera's internal PTS into host monotonic ns.
        val anchor = JsonObject(
            sortedMapOf(
                "anchor_source"        to JsonPrimitive("ble_ack"),
                "ble_ack_monotonic_ns" to JsonPrimitive(bleAckMonotonicNs),
                "stream_id"            to JsonPrimitive(streamId),
            )
        )
        File(episodeDirectory, "$streamId.anchor.json")
            .writeText(json.encodeToString(JsonObject.serializer(), anchor))
        Insta360PendingSidecar.delete(episodeDirectory, streamId)

        return StreamIngestReport(streamId, "$streamId.mp4", frameCount = null)
    }

    override suspend fun disconnect() {
        runCatching { ble.unpair() }
        publishHealth(HealthEvent.StreamDisconnected(streamId, "normal"))
    }

    internal fun adoptHealthHandler(handler: (HealthEvent) -> Unit) {
        adoptedHealthHandler = handler
        handler(HealthEvent.StreamConnected(streamId))
    }

    private suspend fun publishHealth(event: HealthEvent) {
        healthBus?.publish(event)
        adoptedHealthHandler?.invoke(event)
    }

    private fun roleFromStreamId(streamId: String): String = when {
        streamId.endsWith("_ego") -> "ego"
        streamId.endsWith("_left") -> "left"
        streamId.endsWith("_right") -> "right"
        else -> ""
    }

    private fun resolvedDeviceUuid(): String =
        ble.connectedDeviceUuid ?: ble.lastKnownDeviceUUID ?: ""

    private fun resolvedDeviceName(): String =
        ble.connectedDeviceName ?: ble.lastKnownDeviceName ?: ""
}
