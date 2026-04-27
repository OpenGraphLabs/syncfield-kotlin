package io.opengraph.syncfield

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Static metadata declared by every [SyncFieldStream]. The orchestrator
 * uses these flags to decide which streams need a post-stop ingest pass
 * and what their on-disk artifact looks like in `manifest.json`.
 */
@Serializable
data class StreamCapabilities(
    @SerialName("requires_ingest")             val requiresIngest: Boolean = false,
    @SerialName("produces_file")               val producesFile: Boolean = true,
    @SerialName("supports_precise_timestamps") val supportsPreciseTimestamps: Boolean = true,
    @SerialName("provides_audio_track")        val providesAudioTrack: Boolean = false,
)
