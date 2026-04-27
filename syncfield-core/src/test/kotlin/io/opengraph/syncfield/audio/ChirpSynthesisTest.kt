package io.opengraph.syncfield.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class ChirpSynthesisTest {

    @Test
    fun `render produces expected sample count for 500ms at 44_1kHz`() {
        val spec = ChirpSpec.defaultStart
        val out = ChirpSynthesis.render(spec, sampleRate = 44_100.0)
        assertThat(out).hasLength(22_050)
    }

    @Test
    fun `render returns empty array for zero duration`() {
        val spec = ChirpSpec(fromHz = 17_000.0, toHz = 19_000.0,
                             durationMs = 0.0, amplitude = 0.8, envelopeMs = 15.0)
        val out = ChirpSynthesis.render(spec, sampleRate = 44_100.0)
        assertThat(out).isEmpty()
    }

    @Test
    fun `envelope attack starts near zero and rises`() {
        val spec = ChirpSpec.defaultStart
        val out = ChirpSynthesis.render(spec, sampleRate = 44_100.0)
        // envelope is 15 ms × 44.1 kHz = 661 samples; the very first
        // sample is multiplied by ~0 by the cosine envelope so it
        // should be effectively silent.
        assertThat(abs(out[0])).isLessThan(0.001f)
        // By sample 200 we're well into the attack and should see
        // non-trivial amplitude.
        assertThat(abs(out[200])).isGreaterThan(0.05f)
    }

    @Test
    fun `envelope release ends near zero`() {
        val spec = ChirpSpec.defaultStart
        val out = ChirpSynthesis.render(spec, sampleRate = 44_100.0)
        // Last sample is in the release tail — amplitude should be ~0.
        assertThat(abs(out.last())).isLessThan(0.005f)
    }

    @Test
    fun `peak amplitude bounded by spec amplitude`() {
        val spec = ChirpSpec.defaultStart
        val out = ChirpSynthesis.render(spec, sampleRate = 44_100.0)
        val peak = out.maxOf { abs(it) }
        // The cosine envelope can push individual samples slightly past
        // the nominal amplitude due to phase alignment; tolerance 1 %.
        assertThat(peak.toDouble()).isAtMost(spec.amplitude * 1.01)
    }

    @Test
    fun `audible defaults span human-audible band`() {
        assertThat(ChirpSpec.audibleStart.fromHz).isEqualTo(400.0)
        assertThat(ChirpSpec.audibleStart.toHz).isEqualTo(2_500.0)
        assertThat(ChirpSpec.audibleStop.fromHz).isEqualTo(2_500.0)
        assertThat(ChirpSpec.audibleStop.toHz).isEqualTo(400.0)
    }

    @Test
    fun `ultrasonic defaults sit in 17-19 kHz band`() {
        assertThat(ChirpSpec.defaultStart.fromHz).isEqualTo(17_000.0)
        assertThat(ChirpSpec.defaultStart.toHz).isEqualTo(19_000.0)
    }
}
