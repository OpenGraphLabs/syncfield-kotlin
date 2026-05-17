package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [Insta360ProtocolPackets] — the BLE wire-format constants
 * extracted from Insta360's `minicamera-11.9.1` `BleConnectCmd.java`.
 *
 * Why these constants live here:
 *   Android's `InstaCameraManager.connectBle()` rejects GO 3S inside an
 *   obfuscated `IBleConnectDelegate` impl. We therefore drive the GATT
 *   layer ourselves via fastble (`Insta360DirectGattConnector`) and
 *   reproduce the protocol handshake the SDK normally performs inside
 *   `BleConnectCmd.exeCmd`/`notifyAndRead`. The constants here are the
 *   contract between our handshake and the camera firmware — if they
 *   drift from the SDK reference, the camera disconnects within ms of
 *   GATT setup (HCI status=19, REMOTE_USER_TERMINATED_CONNECTION).
 *
 * Reference lines below are against
 * `/tmp/minicam-src-extract/com/arashivision/camera/command/ble/BleConnectCmd.java`.
 */
class Insta360ProtocolPacketsTest {

    @Test
    fun `service UUID short form is be80`() {
        // BleConnectCmd.java:56 — `private static final String SID = "be80";`
        // 16-bit UUID expanded to canonical 128-bit form using the Bluetooth
        // SIG base UUID (0000xxxx-0000-1000-8000-00805f9b34fb).
        assertThat(Insta360ProtocolPackets.SERVICE_UUID_16BIT).isEqualTo("be80")
        assertThat(Insta360ProtocolPackets.SERVICE_UUID_128BIT.toString())
            .isEqualTo("0000be80-0000-1000-8000-00805f9b34fb")
    }

    @Test
    fun `MTU matches SDK reference`() {
        // BleConnectCmd.java:50 — `private static int BLE_MTU = 256;`
        // BleConnectCmd.java:51 — `private static int BLE_WRITE_MAX_LEN = BLE_MTU - 50;`
        assertThat(Insta360ProtocolPackets.MTU).isEqualTo(256)
        assertThat(Insta360ProtocolPackets.WRITE_MAX_LEN).isEqualTo(256 - 50)
    }

    @Test
    fun `sync packet matches SDK byte sequence`() {
        // BleConnectCmd.java:74 — `byte[] syncData = {0xab, 0xba × 5}`
        val expected = byteArrayOf(
            0xAB.toByte(), 0xBA.toByte(),
            0xAB.toByte(), 0xBA.toByte(),
            0xAB.toByte(), 0xBA.toByte(),
            0xAB.toByte(), 0xBA.toByte(),
            0xAB.toByte(), 0xBA.toByte(),
        )
        assertThat(Insta360ProtocolPackets.SYNC_PACKET).isEqualTo(expected)
    }

    @Test
    fun `sync response prefix identifies BLE proxy mode`() {
        // BleConnectCmd.java:283-285 — `if (hexString.startsWith("ff0641"))`
        // marks the connection as `bleProxy = true` (BLE-routed command channel).
        assertThat(Insta360ProtocolPackets.SYNC_RESPONSE_PREFIX_HEX).isEqualTo("ff0641")
    }

    @Test
    fun `wake auth response prefix and success byte index match SDK`() {
        // BleConnectCmd.java:269-282 — parseWakeUpData() expects
        // hexString.startsWith("ff0d03") and reads success at data[5].
        //   data[5] == 0  → wake up authorization failed
        //   data[5] == 1  → wake up authorization success
        assertThat(Insta360ProtocolPackets.WAKE_AUTH_RESPONSE_PREFIX_HEX).isEqualTo("ff0d03")
        assertThat(Insta360ProtocolPackets.WAKE_AUTH_RESULT_BYTE_INDEX).isEqualTo(5)
        assertThat(Insta360ProtocolPackets.WAKE_AUTH_RESULT_SUCCESS).isEqualTo(0x01.toByte())
        assertThat(Insta360ProtocolPackets.WAKE_AUTH_RESULT_FAILURE).isEqualTo(0x00.toByte())
    }

    @Test
    fun `heartbeat write packet matches SDK hex`() {
        // BleConnectCmd.java:386 — the write that triggers the next heartbeat
        // cycle (when `bleHeartDelayExperiment > 0`):
        //   hexString.equals("ff07400700070000000500006b46")
        // We send this exact byte sequence as the heartbeat keepalive write
        // on the protocol session's RW characteristic.
        val expected = hexToBytes("ff07400700070000000500006b46")
        assertThat(Insta360ProtocolPackets.HEARTBEAT_PACKET).isEqualTo(expected)
    }

    @Test
    fun `sync handshake timeout matches SDK reference`() {
        // BleConnectCmd.java:475 — `mThreadHandler.postDelayed(receiveSyncPackTimeoutRunnable, 5000)`
        assertThat(Insta360ProtocolPackets.SYNC_TIMEOUT_MS).isEqualTo(5_000L)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even: ${hex.length}" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
