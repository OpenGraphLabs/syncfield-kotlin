package io.opengraph.syncfield.insta360

import java.util.UUID

/**
 * Insta360 GO-family BLE wire-format constants.
 *
 * These mirror the values used internally by Insta360's
 * `minicamera-11.9.1` `BleConnectCmd` to talk to the camera over BLE
 * after a GATT connection is established. Android's
 * `InstaCameraManager.connectBle()` silently rejects GO 3S, so we drive
 * the GATT channel ourselves (via fastble in
 * [Insta360DirectGattConnector]) and reproduce the protocol handshake
 * here. Drift between these constants and the SDK reference manifests
 * as the camera tearing down GATT within milliseconds of connect
 * (HCI status=19, REMOTE_USER_TERMINATED_CONNECTION).
 *
 * Reference: `BleConnectCmd.java` lines noted alongside each constant.
 */
internal object Insta360ProtocolPackets {

    /** 16-bit form of the Insta360 service used for sync + command IO. */
    // BleConnectCmd.java:56 — `private static final String SID = "be80";`
    const val SERVICE_UUID_16BIT: String = "be80"

    /** 128-bit canonical form (Bluetooth SIG base UUID with 16-bit slot). */
    val SERVICE_UUID_128BIT: UUID = UUID.fromString("0000be80-0000-1000-8000-00805f9b34fb")

    /** ATT MTU the SDK negotiates after GATT connect. */
    // BleConnectCmd.java:50 — `private static int BLE_MTU = 256;`
    const val MTU: Int = 256

    /** Maximum payload per BLE write — MTU minus ATT/L2CAP overhead. */
    // BleConnectCmd.java:51 — `private static int BLE_WRITE_MAX_LEN = BLE_MTU - 50;`
    const val WRITE_MAX_LEN: Int = MTU - 50

    /**
     * Five `0xAB 0xBA` pairs the SDK writes immediately after enabling
     * notify, to flip the camera into BLE-proxied command mode. The
     * camera answers with a frame prefixed by [SYNC_RESPONSE_PREFIX_HEX]
     * once it has accepted the channel.
     */
    // BleConnectCmd.java:74 — `byte[] syncData = {0xab, 0xba × 5 pairs}`
    val SYNC_PACKET: ByteArray = byteArrayOf(
        0xAB.toByte(), 0xBA.toByte(),
        0xAB.toByte(), 0xBA.toByte(),
        0xAB.toByte(), 0xBA.toByte(),
        0xAB.toByte(), 0xBA.toByte(),
        0xAB.toByte(), 0xBA.toByte(),
    )

    /**
     * Prefix of the frame the camera sends back when it accepts our
     * sync packet. Receipt of any frame starting with these bytes means
     * the handshake succeeded and the channel is in BLE-proxy mode
     * (`mOneDrvier.setBleProxy(true)` on the SDK side).
     */
    // BleConnectCmd.java:284 — `if (hexString.startsWith("ff0641")) return true;`
    const val SYNC_RESPONSE_PREFIX_HEX: String = "ff0641"

    /** Hard deadline for receiving [SYNC_RESPONSE_PREFIX_HEX] after the
     *  sync packet is written. */
    // BleConnectCmd.java:475 — `mThreadHandler.postDelayed(receiveSyncPackTimeoutRunnable, 5000)`
    const val SYNC_TIMEOUT_MS: Long = 5_000L

    /**
     * Prefix the camera uses for its wake-up authorization reply. After
     * sync the SDK writes a wake-auth packet derived from a stable
     * client UUID and the camera answers with a `ff0d03 ...` frame
     * whose [WAKE_AUTH_RESULT_BYTE_INDEX] byte indicates the outcome.
     */
    // BleConnectCmd.java:271 — `hexString.startsWith("ff0d03")`
    const val WAKE_AUTH_RESPONSE_PREFIX_HEX: String = "ff0d03"

    /** Offset into the wake-auth response frame holding success/failure. */
    // BleConnectCmd.java:272 — `data[5] == 0` (fail) / `data[5] == 1` (success)
    const val WAKE_AUTH_RESULT_BYTE_INDEX: Int = 5

    /** Success sentinel at [WAKE_AUTH_RESULT_BYTE_INDEX]. */
    const val WAKE_AUTH_RESULT_SUCCESS: Byte = 0x01
    /** Failure sentinel at [WAKE_AUTH_RESULT_BYTE_INDEX]. */
    const val WAKE_AUTH_RESULT_FAILURE: Byte = 0x00

    /**
     * Heartbeat write packet. The SDK uses receipt of this exact byte
     * sequence in the write callback as the signal to schedule the
     * next heartbeat. We send it on a 2 s cadence to keep the BLE link
     * alive while idle.
     */
    // BleConnectCmd.java:386 — `"ff07400700070000000500006b46".equals(dataHexString)`
    val HEARTBEAT_PACKET: ByteArray = hexToBytes("ff07400700070000000500006b46")

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even: ${hex.length}" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
