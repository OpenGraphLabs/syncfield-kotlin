package io.opengraph.syncfield.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioTrackChirpPlayerTest {

    @Test
    fun `pcm16 encoding clamps out-of-range chirp samples`() {
        val encoded = floatArrayOf(-2f, -1f, 0f, 1f, 2f).toPcm16()

        assertThat(encoded.asList()).containsExactly(
            Short.MIN_VALUE,
            Short.MIN_VALUE,
            0.toShort(),
            Short.MAX_VALUE,
            Short.MAX_VALUE,
        ).inOrder()
    }

    @Test
    fun `pcm16 encoding preserves chirp polarity and amplitude`() {
        val encoded = floatArrayOf(-0.5f, 0.25f, 0.5f).toPcm16()

        assertThat(encoded[0]).isEqualTo((-16_384).toShort())
        assertThat(encoded[1]).isEqualTo(8_192.toShort())
        assertThat(encoded[2]).isEqualTo(16_384.toShort())
    }
}
