package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [Insta360BleProtocolSession] — pure-logic portion.
 *
 * The full establish-handshake path (`setMtu` → service discovery →
 * `notify` → write sync) requires a live `BluetoothGatt`, which is only
 * available from the platform. Rather than mock the entire fastble +
 * Android Bluetooth stack here, we cover the protocol decisions in
 * pure helpers:
 *   - response prefix matching (sync vs wake-auth vs other)
 *   - selecting the RW characteristic from a service's char list
 *   - splitting an oversized write into MTU-bounded chunks
 *
 * Live GATT behavior is verified separately through the instrumentation
 * lane and the hardware bring-up logs.
 */
class Insta360BleProtocolSessionTest {

    @Test
    fun `isSyncResponse accepts frames starting with ff0641`() {
        val sync = hexToBytes("ff064102030405")
        assertThat(Insta360BleProtocolSession.isSyncResponse(sync)).isTrue()
    }

    @Test
    fun `isSyncResponse rejects frames with different prefix`() {
        val other = hexToBytes("ff0d030001020304")
        assertThat(Insta360BleProtocolSession.isSyncResponse(other)).isFalse()
    }

    @Test
    fun `isSyncResponse rejects empty and short frames`() {
        assertThat(Insta360BleProtocolSession.isSyncResponse(ByteArray(0))).isFalse()
        assertThat(Insta360BleProtocolSession.isSyncResponse(byteArrayOf(0xFF.toByte(), 0x06))).isFalse()
    }

    @Test
    fun `splitForWrite returns single chunk when payload fits`() {
        val payload = ByteArray(Insta360ProtocolPackets.WRITE_MAX_LEN)
        val chunks = Insta360BleProtocolSession.splitForWrite(payload)
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0]).isEqualTo(payload)
    }

    @Test
    fun `splitForWrite splits oversized payload at WRITE_MAX_LEN boundary`() {
        val size = Insta360ProtocolPackets.WRITE_MAX_LEN + 50
        val payload = ByteArray(size) { (it and 0xFF).toByte() }
        val chunks = Insta360BleProtocolSession.splitForWrite(payload)
        assertThat(chunks).hasSize(2)
        assertThat(chunks[0].size).isEqualTo(Insta360ProtocolPackets.WRITE_MAX_LEN)
        assertThat(chunks[1].size).isEqualTo(50)
        // Round-trip: concatenation reproduces input
        val joined = chunks[0] + chunks[1]
        assertThat(joined).isEqualTo(payload)
    }

    @Test
    fun `splitForWrite returns empty list for empty payload`() {
        val chunks = Insta360BleProtocolSession.splitForWrite(ByteArray(0))
        assertThat(chunks).isEmpty()
    }

    @Test
    fun `pickRwCharacteristic prefers char with READ + WRITE + NOTIFY properties`() {
        // BleConnectCmd.java:188-213 — the SDK loops over `be80` service's
        // chars, picks the one that has both READ+WRITE for `mWriteCharacteristic`,
        // and the one that has NOTIFY for the subscription. In practice the
        // GO 3S exposes a single char that has all three; some firmware
        // splits across two. Our picker prefers the combined char, falls
        // back to the first NOTIFY-capable for subscription.
        val combinedProps =
            android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ or
                android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE or
                android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
        val pick = Insta360BleProtocolSession.pickCharacteristicRole(
            properties = combinedProps,
        )
        assertThat(pick.isReadWrite).isTrue()
        assertThat(pick.isNotify).isTrue()
    }

    @Test
    fun `pickRwCharacteristic recognizes notify-only characteristic`() {
        val pick = Insta360BleProtocolSession.pickCharacteristicRole(
            properties = android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        )
        assertThat(pick.isReadWrite).isFalse()
        assertThat(pick.isNotify).isTrue()
    }

    @Test
    fun `pickRwCharacteristic recognizes read+write-only characteristic`() {
        val pick = Insta360BleProtocolSession.pickCharacteristicRole(
            properties =
                android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ or
                    android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE,
        )
        assertThat(pick.isReadWrite).isTrue()
        assertThat(pick.isNotify).isFalse()
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even: ${hex.length}" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
