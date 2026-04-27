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
import io.opengraph.syncfield.writers.WriterFactory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import java.io.File

/**
 * BLE-triggered Insta360 Go 3S camera stream — Android counterpart of
 * `Insta360CameraStream.swift`.
 *
 * Lifecycle:
 * 1. [prepare] — assert SDK availability.
 * 2. [connect] — BLE pair with the camera.
 * 3. [startRecording] — BLE start-capture; record ACK host time.
 * 4. [stopRecording] — BLE stop-capture; the SDK returns the file URI.
 * 5. [ingest] — fetch WiFi creds over BLE, switch the phone onto the
 *    camera AP, download the mp4, restore the previous network, write
 *    `<streamId>.anchor.json` with the BLE-ACK anchor.
 * 6. [disconnect] — BLE unpair.
 */
class Insta360CameraStream(
    private val context: Context,
    override val streamId: String,
) : SyncFieldStream {

    override val capabilities = StreamCapabilities(
        requiresIngest = true,
        producesFile = true,
        supportsPreciseTimestamps = true,
        providesAudioTrack = true,
    )

    private val ble = Insta360BLEController(context)
    private val wifi = Insta360WiFiDownloader(context)

    private var healthBus: HealthBus? = null
    /** Host-monotonic nanoseconds at the moment the BLE start-capture ACK was received. */
    private var bleAckMonotonicNs: Long = 0L
    private var cameraFileURI: String? = null

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    override suspend fun prepare() {
        if (!Insta360OneSDKBridge.available) throw Insta360Error.FrameworkNotLinked
    }

    override suspend fun connect(context: StreamConnectContext) {
        healthBus = context.healthBus
        ble.pair()
        healthBus?.publish(HealthEvent.StreamConnected(streamId))
    }

    override suspend fun startRecording(clock: SessionClock, writerFactory: WriterFactory) {
        bleAckMonotonicNs = ble.startRemoteRecording(clock)
    }

    override suspend fun stopRecording(): StreamStopReport {
        cameraFileURI = ble.stopRemoteRecording()
        return StreamStopReport(streamId, frameCount = 0, kind = "video")
    }

    override suspend fun ingest(
        episodeDirectory: File,
        progress: (Double) -> Unit,
    ): StreamIngestReport {
        val uri = cameraFileURI ?: throw Insta360Error.DownloadFailed(
            "no camera file uri recorded from stopRecording")

        val (ssid, passphrase) = ble.wifiCredentials()
        val destination = File(episodeDirectory, "$streamId.mp4")
        wifi.download(
            remoteFileURI = uri,
            destination = destination,
            ssid = ssid,
            passphrase = passphrase,
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

        return StreamIngestReport(streamId, "$streamId.mp4", frameCount = null)
    }

    override suspend fun disconnect() {
        runCatching { ble.unpair() }
        healthBus?.publish(HealthEvent.StreamDisconnected(streamId, "normal"))
    }
}
