package io.opengraph.syncfield.streams

import androidx.camera.video.AudioStats
import com.google.common.truth.Truth.assertThat
import io.opengraph.syncfield.HealthEvent
import org.junit.Test

/**
 * Pure-JVM coverage for [AudioStateTracker]. Mirrors the
 * iOS audio-stall watchdog test in `syncfield-swift`'s
 * `SessionOrchestratorAudioRecoveryTests`; we only need to exercise
 * the state-machine transitions here because the wiring into
 * `AndroidCameraStream` is single-line glue that requires a real
 * device to exercise end-to-end.
 */
class AudioStateTrackerTest {

    private val streamId = "cam_ego"

    @Test
    fun `initial DISABLED tick emits nothing`() {
        val tracker = AudioStateTracker(streamId)
        val event = tracker.update(AudioStats.AUDIO_STATE_DISABLED, nowNanos = 1_000)
        assertThat(event).isNull()
    }

    @Test
    fun `initial ACTIVE is baseline and emits nothing`() {
        val tracker = AudioStateTracker(streamId)
        val event = tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 1_000)
        assertThat(event).isNull()
    }

    @Test
    fun `ACTIVE to SOURCE_SILENCED emits AudioStalled once`() {
        val tracker = AudioStateTracker(streamId)
        assertThat(tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 1_000)).isNull()
        val event = tracker.update(AudioStats.AUDIO_STATE_SOURCE_SILENCED, nowNanos = 2_000)
        assertThat(event).isInstanceOf(HealthEvent.AudioStalled::class.java)
        val stalled = event as HealthEvent.AudioStalled
        assertThat(stalled.streamId).isEqualTo(streamId)
        assertThat(stalled.silentForSeconds).isEqualTo(0.0)
    }

    @Test
    fun `subsequent SOURCE_SILENCED ticks do not re-emit`() {
        val tracker = AudioStateTracker(streamId)
        tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 1_000)
        tracker.update(AudioStats.AUDIO_STATE_SOURCE_SILENCED, nowNanos = 2_000)
        val second = tracker.update(AudioStats.AUDIO_STATE_SOURCE_SILENCED, nowNanos = 3_000)
        val third = tracker.update(AudioStats.AUDIO_STATE_SOURCE_SILENCED, nowNanos = 4_000)
        assertThat(second).isNull()
        assertThat(third).isNull()
    }

    @Test
    fun `SOURCE_SILENCED to ACTIVE emits AudioRecovered`() {
        val tracker = AudioStateTracker(streamId)
        tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 1_000)
        tracker.update(AudioStats.AUDIO_STATE_SOURCE_SILENCED, nowNanos = 2_000)
        val recovered = tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 5_000)
        assertThat(recovered).isInstanceOf(HealthEvent.AudioRecovered::class.java)
        assertThat((recovered as HealthEvent.AudioRecovered).streamId).isEqualTo(streamId)
    }

    @Test
    fun `subsequent ACTIVE ticks after recovery do not re-emit`() {
        val tracker = AudioStateTracker(streamId)
        tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 1_000)
        tracker.update(AudioStats.AUDIO_STATE_SOURCE_SILENCED, nowNanos = 2_000)
        tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 5_000)
        val again = tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 6_000)
        assertThat(again).isNull()
    }

    @Test
    fun `SOURCE_ERROR is treated as a stall`() {
        val tracker = AudioStateTracker(streamId)
        tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 1_000)
        val event = tracker.update(AudioStats.AUDIO_STATE_SOURCE_ERROR, nowNanos = 2_000)
        assertThat(event).isInstanceOf(HealthEvent.AudioStalled::class.java)
    }

    @Test
    fun `ENCODER_ERROR is treated as a stall`() {
        val tracker = AudioStateTracker(streamId)
        tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 1_000)
        val event = tracker.update(AudioStats.AUDIO_STATE_ENCODER_ERROR, nowNanos = 2_000)
        assertThat(event).isInstanceOf(HealthEvent.AudioStalled::class.java)
    }

    @Test
    fun `transition between stall types does not re-emit`() {
        val tracker = AudioStateTracker(streamId)
        tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 1_000)
        val firstStall = tracker.update(AudioStats.AUDIO_STATE_SOURCE_SILENCED, nowNanos = 2_000)
        val crossover = tracker.update(AudioStats.AUDIO_STATE_SOURCE_ERROR, nowNanos = 3_000)
        assertThat(firstStall).isInstanceOf(HealthEvent.AudioStalled::class.java)
        assertThat(crossover).isNull()
    }

    @Test
    fun `stall recover stall sequence emits two AudioStalled events`() {
        val tracker = AudioStateTracker(streamId)
        tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 1_000)
        val firstStall = tracker.update(AudioStats.AUDIO_STATE_SOURCE_SILENCED, nowNanos = 2_000)
        val recovered = tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 3_000)
        val secondStall = tracker.update(AudioStats.AUDIO_STATE_SOURCE_SILENCED, nowNanos = 4_000)
        assertThat(firstStall).isInstanceOf(HealthEvent.AudioStalled::class.java)
        assertThat(recovered).isInstanceOf(HealthEvent.AudioRecovered::class.java)
        assertThat(secondStall).isInstanceOf(HealthEvent.AudioStalled::class.java)
    }

    @Test
    fun `DISABLED tick during recording is no-op without dropping stall flag`() {
        // CameraX should never emit DISABLED mid-recording when audio
        // was enabled, but if it ever does we treat it as a no-op so a
        // subsequent ACTIVE doesn't surface a spurious recovery.
        val tracker = AudioStateTracker(streamId)
        tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 1_000)
        tracker.update(AudioStats.AUDIO_STATE_SOURCE_SILENCED, nowNanos = 2_000)
        val disabledMid = tracker.update(AudioStats.AUDIO_STATE_DISABLED, nowNanos = 3_000)
        val recovered = tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 4_000)
        assertThat(disabledMid).isNull()
        // Recovery is emitted from the prior stall state, not from DISABLED.
        assertThat(recovered).isInstanceOf(HealthEvent.AudioRecovered::class.java)
    }

    @Test
    fun `reset clears stall state so subsequent ACTIVE is baseline`() {
        val tracker = AudioStateTracker(streamId)
        tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 1_000)
        tracker.update(AudioStats.AUDIO_STATE_SOURCE_SILENCED, nowNanos = 2_000)
        tracker.reset()
        val afterReset = tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 3_000)
        assertThat(afterReset).isNull()
    }

    @Test
    fun `unknown audioState codes are ignored`() {
        val tracker = AudioStateTracker(streamId)
        tracker.update(AudioStats.AUDIO_STATE_ACTIVE, nowNanos = 1_000)
        val event = tracker.update(audioState = 9999, nowNanos = 2_000)
        assertThat(event).isNull()
    }
}
