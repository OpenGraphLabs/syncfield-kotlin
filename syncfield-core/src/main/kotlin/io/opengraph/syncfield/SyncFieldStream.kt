package io.opengraph.syncfield

import io.opengraph.syncfield.writers.WriterFactory
import java.io.File

/**
 * Context handed to a stream when the orchestrator calls
 * [SyncFieldStream.connect].
 */
data class StreamConnectContext(
    val sessionId: String,
    val hostId: String,
    val healthBus: HealthBus,
)

/**
 * Snapshot of a stream's recording state after [SyncFieldStream.stopRecording].
 */
data class StreamStopReport(
    val streamId: String,
    val frameCount: Int,
    val kind: String,
)

/**
 * Snapshot of a stream's ingest results. `filePath` is relative to the
 * episode directory; `null` when the stream produced no file.
 */
data class StreamIngestReport(
    val streamId: String,
    val filePath: String?,
    val frameCount: Int?,
)

/**
 * Custom adapter contract implemented by every recording source —
 * Android camera, IMU, FSR glove, Insta360, and so on.
 */
interface SyncFieldStream {
    val streamId: String
    val capabilities: StreamCapabilities

    suspend fun prepare()

    suspend fun connect(context: StreamConnectContext)

    suspend fun startRecording(clock: SessionClock, writerFactory: WriterFactory)

    suspend fun stopRecording(): StreamStopReport

    suspend fun ingest(
        episodeDirectory: File,
        progress: (Double) -> Unit,
    ): StreamIngestReport

    suspend fun disconnect()
}
