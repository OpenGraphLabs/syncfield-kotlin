package io.opengraph.syncfield.tactile

import android.content.Context
import io.opengraph.syncfield.HealthBus
import io.opengraph.syncfield.HealthEvent
import io.opengraph.syncfield.SessionClock
import io.opengraph.syncfield.StreamCapabilities
import io.opengraph.syncfield.StreamConnectContext
import io.opengraph.syncfield.StreamIngestReport
import io.opengraph.syncfield.StreamStopReport
import io.opengraph.syncfield.SyncFieldStream
import io.opengraph.syncfield.writers.SensorWriter
import io.opengraph.syncfield.writers.WriterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Live sample emitted by [TactileStream.setSampleHandler] — lets host
 * apps subscribe to real-time sensor values for UI preview, gesture
 * recognition, or custom triggers without touching the on-disk JSONL.
 *
 * The handler fires on the BLE delivery thread, not the main thread;
 * marshal to your UI thread yourself if the handler updates UI.
 */
data class TactileSampleEvent(
    val streamId: String,
    val side: TactileSide,
    val frame: Int,
    /** Host monotonic nanoseconds (same domain as [SessionClock.nowMonotonicNs]). */
    val monotonicNs: Long,
    /** Firmware hardware clock in nanoseconds. */
    val deviceTimestampNs: Long,
    /** Channel label → raw 12-bit FSR value. Labels come from the firmware manifest. */
    val channels: Map<String, Int>,
)

/**
 * Android port of `TactileStream`. Bridges [TactileBLEClient] notifications
 * into the SyncField pipeline: each BLE packet expands into N sample
 * rows in `<streamId>.jsonl`, one per FSR sample, with per-sample
 * timestamps interpolated from the batch arrival time.
 */
class TactileStream(
    private val context: Context,
    override val streamId: String,
    val side: TactileSide,
) : SyncFieldStream {

    override val capabilities = StreamCapabilities(
        requiresIngest = false,
        producesFile = false,
        supportsPreciseTimestamps = true,
    )

    private val client = TactileBLEClient(context)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var writer: SensorWriter? = null
    private var clock: SessionClock? = null
    private var healthBus: HealthBus? = null
    private var manifest: DeviceManifest? = null
    @Volatile private var frameCount: Int = 0
    @Volatile private var isSubscribed: Boolean = false

    private val sampleHandlerLock = Object()
    private var _sampleHandler: ((TactileSampleEvent) -> Unit)? = null

    /** Attach a live sample handler. Pass `null` to clear. Thread-safe. */
    fun setSampleHandler(handler: ((TactileSampleEvent) -> Unit)?) {
        synchronized(sampleHandlerLock) { _sampleHandler = handler }
    }

    override suspend fun prepare() {}

    override suspend fun connect(context: StreamConnectContext) {
        healthBus = context.healthBus
        val ref = client.scan()
        manifest = client.connectAndPrepare(ref, expectedSide = side)
        // Subscribe immediately so a host UI gets preview data before
        // recording starts.
        client.subscribe { data, arrivalNs -> handlePacket(data, arrivalNs) }
        isSubscribed = true
        healthBus?.publish(HealthEvent.StreamConnected(streamId))
    }

    override suspend fun startRecording(clock: SessionClock, writerFactory: WriterFactory) {
        this.clock = clock
        this.writer = writerFactory.makeSensorWriter(streamId)
        frameCount = 0
    }

    override suspend fun stopRecording(): StreamStopReport {
        val final = frameCount
        runCatching { writer?.close() }
        writer = null
        return StreamStopReport(streamId, frameCount = final, kind = "sensor")
    }

    override suspend fun ingest(
        episodeDirectory: File,
        progress: (Double) -> Unit,
    ): StreamIngestReport = StreamIngestReport(streamId, "$streamId.jsonl", frameCount)

    override suspend fun disconnect() {
        isSubscribed = false
        setSampleHandler(null)
        client.disconnect()
        healthBus?.publish(HealthEvent.StreamDisconnected(streamId, "normal"))
    }

    private fun handlePacket(data: ByteArray, arrivalNs: Long) {
        if (!isSubscribed) return
        val m = manifest ?: return
        val packet = runCatching { TactilePacketParser.parse(data) }.getOrNull() ?: return

        val intervalUs = TactileConstants.SAMPLE_INTERVAL_US
        val handler = synchronized(sampleHandlerLock) { _sampleHandler }

        for ((sampleIdx, channels) in packet.samples.withIndex()) {
            val frame = frameCount
            frameCount += 1

            val captureNs = arrivalNs + sampleIdx * intervalUs * 1_000L
            val deviceTsNs = (packet.batchTimestampUs + sampleIdx * intervalUs) * 1_000L

            val labelled = LinkedHashMap<String, Int>(channels.size)
            for ((cid, raw) in channels.withIndex()) {
                val label = m.locationForChannel(cid) ?: "ch$cid"
                labelled[label] = raw
            }

            handler?.invoke(
                TactileSampleEvent(
                    streamId = streamId,
                    side = side,
                    frame = frame,
                    monotonicNs = captureNs,
                    deviceTimestampNs = deviceTsNs,
                    channels = labelled,
                )
            )

            val w = writer
            if (w != null) {
                ioScope.launch {
                    runCatching {
                        w.append(
                            frame = frame,
                            monotonicNs = captureNs,
                            channels = labelled.mapValues { it.value as Any },
                            deviceTimestampNs = deviceTsNs,
                        )
                    }
                }
            }
        }
    }
}
