package io.opengraph.syncfield.audio

/**
 * Abstraction over tone emission. The default Android implementation is
 * [io.opengraph.syncfield.audio.AudioTrackChirpPlayer] (in syncfield-streams
 * — kept out of core so the core module has no Android-runtime
 * dependencies). [SilentChirpPlayer] is the safe fallback for tests or
 * hosts without audio output.
 */
interface ChirpPlayer {
    val isSilent: Boolean

    suspend fun play(spec: ChirpSpec): ChirpEmission
}

/** No-op player. Records the call time and reports `Silent`. */
class SilentChirpPlayer : ChirpPlayer {
    override val isSilent: Boolean = true

    override suspend fun play(spec: ChirpSpec): ChirpEmission {
        val now = System.nanoTime()
        return ChirpEmission(softwareNs = now, hardwareNs = null, source = ChirpSource.Silent)
    }
}
