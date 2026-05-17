package io.opengraph.syncfield.insta360

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.clj.fastble.data.BleDevice
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import java.lang.reflect.Constructor

/**
 * Direct Android-platform BLE scanner — used as a parallel/fallback path
 * alongside Insta360's `InstaCameraManager.startBleScan()` because the
 * official SDK's internal filter pipeline (`onScanning` →
 * `getSupportCameraType().contains(type)`) drops every advertisement when
 * the per-app camera support config hasn't been loaded yet — and that
 * config can only load AFTER a camera is paired (chicken-and-egg).
 *
 * This scanner subscribes to the platform's `BluetoothLeScanner` directly,
 * accepts any advertisement whose device name matches the GO-family
 * filter, and forwards it via the supplied [onDiscovered] callback as a
 * fastble `BleDevice` so the rest of the pipeline (pair/connect via
 * SDK's `InstaCameraManager.connectBle(BleDevice)`) keeps working.
 *
 * Lifecycle:
 *   `start()` — begin scanning. Idempotent.
 *   `stop()`  — stop scanning. Idempotent.
 */
internal class Insta360NativeBleScanner(
    private val context: Context,
    private val onDiscovered: (BleDevice) -> Unit,
) {

    @Volatile private var scanning = false
    private var scanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanning) return
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: run {
                InstaLog.log(
                    InstaLogCategory.SCAN, level = InstaLogLevel.WARN,
                    event = "native_scan_unavailable", fields = mapOf("reason" to "no_bt_manager"),
                )
                return
            }
        val adapter = mgr.adapter ?: run {
            InstaLog.log(
                InstaLogCategory.SCAN, level = InstaLogLevel.WARN,
                event = "native_scan_unavailable", fields = mapOf("reason" to "no_adapter"),
            )
            return
        }
        if (!adapter.isEnabled) {
            InstaLog.log(
                InstaLogCategory.SCAN, level = InstaLogLevel.WARN,
                event = "native_scan_unavailable", fields = mapOf("reason" to "bt_off"),
            )
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            InstaLog.log(
                InstaLogCategory.SCAN, level = InstaLogLevel.WARN,
                event = "native_scan_unavailable", fields = mapOf("reason" to "no_scanner"),
            )
            return
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleResult(result)
            }
            override fun onBatchScanResults(results: List<ScanResult>) {
                for (r in results) handleResult(r)
            }
            override fun onScanFailed(errorCode: Int) {
                InstaLog.log(
                    InstaLogCategory.SCAN, level = InstaLogLevel.WARN,
                    event = "native_scan_failed", fields = mapOf("error_code" to errorCode),
                )
                scanning = false
            }
        }
        scanCallback = cb
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching {
            scanner.startScan(null, settings, cb)
            scanning = true
            InstaLog.log(InstaLogCategory.SCAN, event = "native_scan_started")
        }.onFailure {
            InstaLog.log(
                InstaLogCategory.SCAN, level = InstaLogLevel.WARN,
                event = "native_scan_start_threw",
                fields = mapOf("error" to (it.message ?: it::class.java.simpleName)),
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = mgr?.adapter?.bluetoothLeScanner
        scanCallback?.let { runCatching { scanner?.stopScan(it) } }
        scanCallback = null
        scanning = false
        InstaLog.log(InstaLogCategory.SCAN, event = "native_scan_stopped")
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        val device = result.device ?: return
        // Prefer scanRecord name (advertised) over BluetoothDevice.name
        // (cached, may require BLUETOOTH_CONNECT on Android 12+).
        val advName = runCatching { result.scanRecord?.deviceName }.getOrNull()
        val devName = runCatching { device.name }.getOrNull()
        val name = advName ?: devName ?: ""

        // Log every result so we can debug filter mismatches in the field.
        InstaLog.log(
            InstaLogCategory.SCAN, level = InstaLogLevel.DEBUG,
            event = "native_scan_result",
            fields = mapOf(
                "name" to name,
                "mac" to device.address,
                "rssi" to result.rssi,
                "accepted" to Insta360BluetoothHub.shouldEmitDevice(name),
            ),
        )

        if (!Insta360BluetoothHub.shouldEmitDevice(name)) return

        val bleDevice = synthesizeBleDevice(device, result) ?: return
        runCatching { onDiscovered(bleDevice) }.onFailure {
            InstaLog.log(
                InstaLogCategory.SCAN, level = InstaLogLevel.WARN,
                event = "native_scan_forward_threw",
                fields = mapOf("error" to (it.message ?: it::class.java.simpleName)),
            )
        }
    }

    /**
     * Construct a fastble [BleDevice] from a platform [ScanResult].
     *
     * Insta360 ships a fork of fastble 3.0.76 with the constructor
     *   `BleDevice(BluetoothDevice, String deviceName, int rssi,
     *              byte[] scanRecord, long timestampNanos,
     *              SparseArray<byte[]> manufacturerData,
     *              List<ParcelUuid> serviceUuids)`.
     * We prefer this exact signature; reflection is used to stay robust
     * against minor fork variants.
     */
    @SuppressLint("MissingPermission")
    private fun synthesizeBleDevice(device: BluetoothDevice, result: ScanResult): BleDevice? {
        val scanRecord = result.scanRecord
        val scanRecordBytes = scanRecord?.bytes ?: ByteArray(0)
        val advName = scanRecord?.deviceName
        val devName = runCatching { device.name }.getOrNull()
        val deviceName: String = advName ?: devName ?: ""
        val manufacturerData = scanRecord?.manufacturerSpecificData
            ?: android.util.SparseArray()
        val serviceUuids = scanRecord?.serviceUuids ?: emptyList()

        return runCatching {
            val ctor: Constructor<BleDevice> = BleDevice::class.java.getConstructor(
                BluetoothDevice::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
                Long::class.javaPrimitiveType,
                android.util.SparseArray::class.java,
                List::class.java,
            )
            ctor.newInstance(
                device,
                deviceName,
                result.rssi,
                scanRecordBytes,
                result.timestampNanos,
                manufacturerData,
                serviceUuids,
            )
        }.recoverCatching {
            // 8-arg variant (some fastble forks add a trailing boolean for
            // "from scan callback").
            val ctor: Constructor<BleDevice> = BleDevice::class.java.getConstructor(
                BluetoothDevice::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
                Long::class.javaPrimitiveType,
                android.util.SparseArray::class.java,
                List::class.java,
                Boolean::class.javaPrimitiveType,
            )
            ctor.newInstance(
                device,
                deviceName,
                result.rssi,
                scanRecordBytes,
                result.timestampNanos,
                manufacturerData,
                serviceUuids,
                true,
            )
        }.onFailure {
            InstaLog.log(
                InstaLogCategory.SCAN, level = InstaLogLevel.WARN,
                event = "native_scan_synthesize_failed",
                fields = mapOf("error" to (it.message ?: it::class.java.simpleName)),
            )
        }.getOrNull()
    }
}
