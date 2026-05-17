package io.opengraph.syncfield.insta360

import android.bluetooth.BluetoothGatt
import com.clj.fastble.BleManager
import com.clj.fastble.callback.BleGattCallback
import com.clj.fastble.data.BleDevice
import com.clj.fastble.exception.BleException
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS-style direct GATT connector for Insta360 GO 3S.
 *
 * iOS pairs cameras through `INSBluetoothManager.connect(device, completion)`
 * — the lower of the two camera-API layers, the one that wraps
 * `CBCentralManager` directly without the high-level camera-type filter
 * that `INSCameraManager` applies. GO 3S works on iOS precisely because
 * the lower layer doesn't care whether the camera type is in the SDK's
 * "supported" list.
 *
 * On Android, `InstaCameraManager.connectBle(device)` (the high layer)
 * silently rejects GO 3S inside an obfuscated `IBleConnectDelegate`
 * impl even after the [Insta360SupportConfigWrapper] is installed —
 * `connectBle_state_poll` never observes `connected_type` change and
 * fastble's own `BleConnectCmd.exeCmd` never runs. So this connector
 * does the iOS thing: skip the high-layer SDK path and drive the
 * lower layer (fastble's `BleManager`) directly.
 *
 * fastble is the GATT library the SDK ships and uses internally, so
 * there's only one instance per process. Calling it from us is the
 * same call the SDK would have made — minus the gate.
 *
 * Lifecycle: this is a connect primitive only. Higher-layer protocol
 * setup (MTU bump, notify on service `be80`, sync-packet handshake)
 * and command issuance are layered on top by callers.
 */
internal object Insta360DirectGattConnector {

    /**
     * GATT-connect to [device] via fastble. Resumes with the live
     * [BluetoothGatt] when `onConnectSuccess` fires, or throws
     * [Insta360Error.CommandFailed] on `onConnectFail`.
     *
     * Cancellation propagates: callers using `withTimeout` will leave
     * the fastble manager free to retry; fastble's internal callback
     * for the orphaned attempt completes harmlessly after we resume.
     */
    suspend fun connect(device: BleDevice): BluetoothGatt =
        suspendCancellableCoroutine { cont ->
            val mgr = BleManager.getInstance()
            InstaLog.log(
                InstaLogCategory.BLE, event = "direct_gatt_connect_invoke",
                fields = mapOf(
                    "name" to (device.name ?: ""),
                    "mac" to (device.mac ?: ""),
                    "rssi" to device.rssi,
                ),
            )
            mgr.connect(device, object : BleGattCallback() {
                override fun onStartConnect() {
                    InstaLog.log(
                        InstaLogCategory.BLE,
                        event = "direct_gatt_onStartConnect",
                    )
                }

                override fun onConnectFail(bleDevice: BleDevice, exception: BleException) {
                    InstaLog.log(
                        InstaLogCategory.BLE, level = InstaLogLevel.WARN,
                        event = "direct_gatt_onConnectFail",
                        fields = mapOf(
                            "code" to exception.code,
                            "desc" to (exception.description ?: ""),
                        ),
                    )
                    if (!cont.isCompleted) {
                        cont.resumeWithException(
                            Insta360Error.CommandFailed(
                                "direct GATT connect failed code=${exception.code} desc=${exception.description}"
                            )
                        )
                    }
                }

                override fun onConnectSuccess(
                    bleDevice: BleDevice,
                    gatt: BluetoothGatt,
                    status: Int,
                ) {
                    InstaLog.log(
                        InstaLogCategory.BLE,
                        event = "direct_gatt_onConnectSuccess",
                        fields = mapOf(
                            "status" to status,
                            "services_count" to (gatt.services?.size ?: 0),
                        ),
                    )
                    if (!cont.isCompleted) cont.resume(gatt)
                }

                override fun onDisConnected(
                    isActiveDisConnected: Boolean,
                    bleDevice: BleDevice,
                    gatt: BluetoothGatt,
                    status: Int,
                ) {
                    InstaLog.log(
                        InstaLogCategory.BLE, level = InstaLogLevel.WARN,
                        event = "direct_gatt_onDisConnected",
                        fields = mapOf(
                            "active" to isActiveDisConnected,
                            "status" to status,
                        ),
                    )
                }
            })
        }
}
