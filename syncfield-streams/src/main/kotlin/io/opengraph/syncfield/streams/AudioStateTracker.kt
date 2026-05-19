package io.opengraph.syncfield.streams

import androidx.camera.video.AudioStats
import io.opengraph.syncfield.HealthEvent

/**
 * Translates CameraX [AudioStats.audioState] transitions into
 * [HealthEvent.AudioStalled] / [HealthEvent.AudioRecovered] emissions.
 *
 * Used by [AndroidCameraStream] to expose audio-interruption signals
 * (incoming call, voice-assistant capture, mic source errors) that
 * CameraX already publishes inside its [androidx.camera.video.VideoRecordEvent.Status]
 * events but which the stream previously ignored.
 *
 * Pure logic on top of `Int` audio state codes — no Android runtime
 * dependencies beyond the [AudioStats] constants — so the JVM unit
 * test in `AudioStateTrackerTest` can exercise every transition
 * without an emulator.
 *
 * Contract:
 * - `ACTIVE` is the only "audio is flowing" state. Any other non-DISABLED
 *   state is treated as a stall.
 * - `DISABLED` is the baseline state when the recording was started
 *   without `withAudioEnabled()` (e.g., RECORD_AUDIO permission was
 *   revoked). It produces no events at all, and a subsequent transition
 *   from `DISABLED` to `ACTIVE` is treated as the initial baseline —
 *   no spurious `AudioRecovered` is emitted.
 * - Same-state ticks are deduped: the tracker only emits on a true
 *   transition across the stall boundary.
 */
internal class AudioStateTracker(private val streamId: String) {

    private var stalled: Boolean = false
    private var stallStartNanos: Long = 0L

    /**
     * Feed the latest CameraX audio state. Returns a [HealthEvent] to
     * publish, or `null` if the transition is a no-op (same state,
     * baseline state, or a transition that does not cross the stall
     * boundary).
     *
     * Safe to call from any thread, but callers must serialize calls
     * for a single tracker instance — AndroidCameraStream does this
     * naturally by calling from CameraX's single-thread recording
     * executor.
     */
    fun update(audioState: Int, nowNanos: Long): HealthEvent? {
        return when (audioState) {
            AudioStats.AUDIO_STATE_ACTIVE -> handleActive()
            AudioStats.AUDIO_STATE_SOURCE_SILENCED,
            AudioStats.AUDIO_STATE_SOURCE_ERROR,
            AudioStats.AUDIO_STATE_ENCODER_ERROR -> handleStall(nowNanos)
            AudioStats.AUDIO_STATE_DISABLED -> null
            else -> null
        }
    }

    fun reset() {
        stalled = false
        stallStartNanos = 0L
    }

    private fun handleActive(): HealthEvent? {
        if (!stalled) return null
        stalled = false
        stallStartNanos = 0L
        return HealthEvent.AudioRecovered(streamId)
    }

    private fun handleStall(nowNanos: Long): HealthEvent? {
        if (stalled) return null
        stalled = true
        stallStartNanos = nowNanos
        // CameraX surfaces the transition synchronously on the
        // Status tick, so the actual silent duration up to this
        // emission point is effectively zero. Hosts that want a
        // running "silent for N seconds" counter accumulate it
        // themselves against their own clock (see RecordingScreen.tsx).
        return HealthEvent.AudioStalled(streamId, silentForSeconds = 0.0)
    }
}
