package io.opengraph.syncfield.tactile

/**
 * One BLE packet from the Oglo glove. `samples[sampleIndex][channelIndex]`
 * is a raw 12-bit FSR value packed in the lower bits of a `UShort`.
 */
data class TactilePacket(
    val count: Int,
    val batchTimestampUs: Long,
    val samples: Array<IntArray>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TactilePacket) return false
        if (count != other.count) return false
        if (batchTimestampUs != other.batchTimestampUs) return false
        if (samples.size != other.samples.size) return false
        for (i in samples.indices) {
            if (!samples[i].contentEquals(other.samples[i])) return false
        }
        return true
    }
    override fun hashCode(): Int {
        var r = count
        r = 31 * r + batchTimestampUs.hashCode()
        for (row in samples) r = 31 * r + row.contentHashCode()
        return r
    }
}

/**
 * Pure-logic parser for Oglo glove BLE packets. Wire format:
 *
 * ```
 * offset 0:  uint16 LE  count
 * offset 2:  uint32 LE  batchTimestampUs
 * offset 6:  count × (5 × uint16 LE) = count × 10 bytes
 * ```
 *
 * Equivalent to `TactilePacketParser.swift`. Out-of-bounds inputs raise
 * [Truncated] (header missing) or [SizeMismatch] (sample area too short).
 */
object TactilePacketParser {

    class Truncated : Exception("tactile packet truncated")
    class SizeMismatch : Exception("tactile packet size mismatch")

    fun parse(data: ByteArray): TactilePacket {
        if (data.size < TactileConstants.PACKET_HEADER_BYTES) throw Truncated()

        val count = u16LE(data, 0)
        val batchTsUs = u32LE(data, 2)

        val expected = TactileConstants.PACKET_HEADER_BYTES +
            count * TactileConstants.BYTES_PER_SAMPLE
        if (data.size < expected) throw SizeMismatch()

        val samples = Array(count) { sampleIndex ->
            val base = TactileConstants.PACKET_HEADER_BYTES +
                sampleIndex * TactileConstants.BYTES_PER_SAMPLE
            IntArray(TactileConstants.CHANNELS_PER_SAMPLE) { ch ->
                u16LE(data, base + ch * 2)
            }
        }
        return TactilePacket(count, batchTsUs, samples)
    }

    private fun u16LE(d: ByteArray, offset: Int): Int =
        (d[offset].toInt() and 0xFF) or ((d[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32LE(d: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 4) {
            v = v or ((d[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }
}
