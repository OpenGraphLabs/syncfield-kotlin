package io.opengraph.syncfield.tactile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TactilePacketParserTest {

    @Test
    fun `parses a single-sample packet`() {
        // count=1, batchTs=0x00010203 (= 66051), values: [10, 20, 30, 40, 50]
        val data = byteArrayOf(
            0x01, 0x00,                     // count = 1
            0x03, 0x02, 0x01, 0x00,         // batchTs = 66051
            10, 0, 20, 0, 30, 0, 40, 0, 50, 0,
        )
        val pkt = TactilePacketParser.parse(data)
        assertThat(pkt.count).isEqualTo(1)
        assertThat(pkt.batchTimestampUs).isEqualTo(66_051L)
        assertThat(pkt.samples).hasLength(1)
        assertThat(pkt.samples[0]).asList().containsExactly(10, 20, 30, 40, 50).inOrder()
    }

    @Test
    fun `parses a multi-sample packet with non-trivial values`() {
        // count=2, batchTs=1_000_000, two samples of all-1024
        val data = byteArrayOf(
            0x02, 0x00,
            0x40.toByte(), 0x42.toByte(), 0x0F, 0x00,  // 1_000_000
            0x00, 0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x04,
            0x00, 0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x04,
        )
        val pkt = TactilePacketParser.parse(data)
        assertThat(pkt.count).isEqualTo(2)
        assertThat(pkt.batchTimestampUs).isEqualTo(1_000_000L)
        assertThat(pkt.samples[0]).asList()
            .containsExactly(1024, 1024, 1024, 1024, 1024).inOrder()
        assertThat(pkt.samples[1]).asList()
            .containsExactly(1024, 1024, 1024, 1024, 1024).inOrder()
    }

    @Test
    fun `truncated header throws Truncated`() {
        val data = byteArrayOf(0x01, 0x00, 0x00)
        runCatching { TactilePacketParser.parse(data) }
            .onSuccess { error("expected throw") }
            .onFailure { assertThat(it).isInstanceOf(TactilePacketParser.Truncated::class.java) }
    }

    @Test
    fun `size mismatch when count exceeds payload throws SizeMismatch`() {
        // claim 5 samples but only ship one
        val data = byteArrayOf(
            0x05, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        runCatching { TactilePacketParser.parse(data) }
            .onSuccess { error("expected throw") }
            .onFailure { assertThat(it).isInstanceOf(TactilePacketParser.SizeMismatch::class.java) }
    }

    @Test
    fun `zero-sample packet is allowed and yields empty samples`() {
        val data = byteArrayOf(
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val pkt = TactilePacketParser.parse(data)
        assertThat(pkt.count).isEqualTo(0)
        assertThat(pkt.samples).hasLength(0)
    }
}
