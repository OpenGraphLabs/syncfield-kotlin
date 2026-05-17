package io.opengraph.syncfield.insta360

import com.clj.fastble.data.BleDevice
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import kotlinx.coroutines.withTimeout

/**
 * Composite GATT + protocol handshake that mirrors iOS's
 * `INSBluetoothManager.connect(device, completion)` — a single
 * call that resolves only once the channel is end-to-end ready
 * for command IO.
 *
 * Internally:
 *   1. [Insta360DirectGattConnector.connect] establishes a fastble
 *      `BleManager.connect()` GATT link to the device.
 *   2. [Insta360BleProtocolSession.establish] negotiates MTU,
 *      enables notify on the `be80` service, writes the sync
 *      packet, and waits for the camera's sync acknowledgement.
 *
 * The whole handshake has a 20 s budget (15 s GATT + 5 s sync). The
 * underlying SDK uses 10 s for GATT + 5 s for sync; we give a few extra
 * seconds because Android's BLE stack tends to take longer than iOS to
 * report `onClientConnectionState`.
 */
internal object Insta360GattHandshake {

    private const val GATT_TIMEOUT_MS = 15_000L

    /**
     * @return a live [Insta360BleProtocolSession] ready for
     *   [Insta360BleProtocolSession.sendCommand].
     * @throws Insta360Error.CommandFailed if any stage fails.
     */
    suspend fun handshake(device: BleDevice): Insta360BleProtocolSession {
        InstaLog.log(
            InstaLogCategory.BLE, event = "gatt_handshake_start",
            fields = mapOf(
                "name" to (device.name ?: ""),
                "mac" to (device.mac ?: ""),
            ),
        )
        val gatt = try {
            withTimeout(GATT_TIMEOUT_MS) {
                Insta360DirectGattConnector.connect(device)
            }
        } catch (t: Throwable) {
            InstaLog.log(
                InstaLogCategory.BLE, level = InstaLogLevel.WARN,
                event = "gatt_handshake_gatt_stage_failed",
                fields = mapOf("error" to (t.message ?: t::class.java.simpleName)),
            )
            throw t
        }

        // The GATT layer is now up, but the camera will tear it down
        // within ~5 ms unless we satisfy its sync-packet expectation.
        // Run the protocol-session establishment immediately.
        val session = try {
            Insta360BleProtocolSession.establish(device = device, gatt = gatt)
        } catch (t: Throwable) {
            InstaLog.log(
                InstaLogCategory.BLE, level = InstaLogLevel.WARN,
                event = "gatt_handshake_protocol_stage_failed",
                fields = mapOf("error" to (t.message ?: t::class.java.simpleName)),
            )
            // Best-effort: drop the GATT connection we opened so fastble
            // doesn't keep a stale BleBluetooth around for next attempt.
            runCatching {
                com.clj.fastble.BleManager.getInstance().disconnect(device)
            }
            throw t
        }

        InstaLog.log(
            InstaLogCategory.BLE,
            event = "gatt_handshake_complete",
            fields = mapOf(
                "name" to (device.name ?: ""),
                "mac" to (device.mac ?: ""),
            ),
        )
        return session
    }
}
