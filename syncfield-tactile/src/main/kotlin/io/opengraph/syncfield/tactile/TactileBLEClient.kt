package io.opengraph.syncfield.tactile

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight wrapper around BluetoothLeScanner + BluetoothGatt for a
 * single Oglo glove. One [TactileStream] owns one client.
 *
 * The client coalesces all callbacks into suspending APIs:
 * - [scan] returns a [PeripheralRef] for the first matching device or
 *   throws [Errors.ScanTimeout].
 * - [connectAndPrepare] connects, discovers services, reads the config
 *   characteristic, parses [DeviceManifest], and validates `side`.
 * - [subscribe] enables CCCD notifications and forwards each packet to
 *   a host-supplied handler with a host-monotonic arrival timestamp.
 *
 * Caller is responsible for:
 * - Granting `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (Android 12+) or
 *   `BLUETOOTH` / `ACCESS_FINE_LOCATION` (Android 11 and below).
 * - Surfacing user-visible permission flows; this class throws
 *   [SecurityException] if permissions are missing rather than silently
 *   degrading.
 */
@SuppressLint("MissingPermission")
class TactileBLEClient(private val context: Context) {

    sealed class Errors(message: String) : Exception(message) {
        object BluetoothUnavailable : Errors("Bluetooth not available on this device")
        object ScanTimeout : Errors("BLE scan timed out before finding a tactile peripheral")
        class WrongSide(val expected: TactileSide, val actual: TactileSide) :
            Errors("Tactile peripheral reported side=$actual, expected $expected")
        class Disconnected(val reason: String?) :
            Errors("BLE peripheral disconnected${if (reason != null) ": $reason" else ""}")
        object MissingCharacteristic :
            Errors("Required tactile GATT characteristic was not found")
        class ManifestParseFailed(cause: Throwable) :
            Errors("Failed to parse device manifest JSON: ${cause.message}") {
            init { initCause(cause) }
        }
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val scanner: BluetoothLeScanner?
        get() = adapter?.bluetoothLeScanner

    private val gattRef: AtomicReference<BluetoothGatt?> = AtomicReference(null)
    private var sensorChar: BluetoothGattCharacteristic? = null
    private var configChar: BluetoothGattCharacteristic? = null
    private var notifyHandler: ((ByteArray, Long) -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true }

    /** Opaque handle returned by [scan] and consumed by [connectAndPrepare]. */
    class PeripheralRef internal constructor(val device: BluetoothDevice)

    /**
     * Scan briefly for the first peripheral whose name contains
     * `TactileConstants.NAME_FILTER` (case-insensitive).
     */
    suspend fun scan(timeoutMs: Long = 15_000): PeripheralRef {
        val s = scanner ?: throw Errors.BluetoothUnavailable
        val deferred = CompletableDeferred<PeripheralRef>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = (result.device.name ?: "").lowercase()
                if (!name.contains(TactileConstants.NAME_FILTER)) return
                if (deferred.isCompleted) return
                deferred.complete(PeripheralRef(result.device))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        s.startScan(emptyList<ScanFilter>(), settings, callback)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (t: TimeoutCancellationException) {
            throw Errors.ScanTimeout
        } finally {
            runCatching { s.stopScan(callback) }
        }
    }

    /**
     * Connect, discover services, read the config characteristic, parse
     * its JSON manifest, and validate `manifest.side == expectedSide`.
     */
    suspend fun connectAndPrepare(
        ref: PeripheralRef,
        expectedSide: TactileSide,
    ): DeviceManifest {
        val connected = CompletableDeferred<Unit>()
        val servicesReady = CompletableDeferred<Unit>()
        val configValue = CompletableDeferred<ByteArray>()

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                    if (!connected.isCompleted) connected.complete(Unit)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    notifyHandler = null
                    runCatching { g.close() }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(TactileConstants.SERVICE_UUID)
                if (service == null) {
                    if (!servicesReady.isCompleted)
                        servicesReady.completeExceptionally(Errors.MissingCharacteristic)
                    return
                }
                sensorChar = service.getCharacteristic(TactileConstants.SENSOR_CHAR_UUID)
                configChar = service.getCharacteristic(TactileConstants.CONFIG_CHAR_UUID)
                if (sensorChar == null || configChar == null) {
                    if (!servicesReady.isCompleted)
                        servicesReady.completeExceptionally(Errors.MissingCharacteristic)
                    return
                }
                if (!servicesReady.isCompleted) servicesReady.complete(Unit)
            }

            // Pre–API 33 callback. The new (`gatt, char, value`)
            // override below replaces it on API 33+, but we keep this
            // one too so phones running Android 12 and earlier still
            // receive notifications.
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt, char: BluetoothGattCharacteristic,
            ) {
                if (char.uuid != TactileConstants.SENSOR_CHAR_UUID) return
                val arrivalNs = System.nanoTime()
                notifyHandler?.invoke(char.value ?: ByteArray(0), arrivalNs)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray,
            ) {
                if (char.uuid != TactileConstants.SENSOR_CHAR_UUID) return
                val arrivalNs = System.nanoTime()
                notifyHandler?.invoke(value, arrivalNs)
            }

            // Pre–API 33 read callback (paired with the new
            // (`gatt, char, value, status`) override below).
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int,
            ) {
                if (char.uuid != TactileConstants.CONFIG_CHAR_UUID) return
                if (configValue.isCompleted) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    configValue.complete(char.value ?: ByteArray(0))
                } else {
                    configValue.completeExceptionally(Errors.MissingCharacteristic)
                }
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                char: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (char.uuid != TactileConstants.CONFIG_CHAR_UUID) return
                if (configValue.isCompleted) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    configValue.complete(value)
                } else {
                    configValue.completeExceptionally(Errors.MissingCharacteristic)
                }
            }
        }

        val gatt = ref.device.connectGatt(context, /* autoConnect = */ false, gattCallback)
            ?: throw Errors.BluetoothUnavailable
        gattRef.set(gatt)

        connected.await()
        servicesReady.await()

        gatt.readCharacteristic(configChar)
        val raw = configValue.await()

        val manifest = try {
            json.decodeFromString(DeviceManifest.serializer(), String(raw, Charsets.UTF_8))
        } catch (t: Throwable) {
            throw Errors.ManifestParseFailed(t)
        }
        if (manifest.side != expectedSide) {
            throw Errors.WrongSide(expectedSide, manifest.side)
        }
        return manifest
    }

    /**
     * Enable CCCD-driven notifications on the sensor characteristic and
     * forward every packet to [handler] with a host-monotonic timestamp
     * (`System.nanoTime()`).
     */
    fun subscribe(handler: (ByteArray, Long) -> Unit) {
        val gatt = gattRef.get() ?: throw Errors.MissingCharacteristic
        val char = sensorChar ?: throw Errors.MissingCharacteristic
        notifyHandler = handler

        gatt.setCharacteristicNotification(char, true)
        val cccd: BluetoothGattDescriptor = char.getDescriptor(TactileConstants.CCCD_UUID)
            ?: throw Errors.MissingCharacteristic
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(cccd)
    }

    fun disconnect() {
        notifyHandler = null
        sensorChar = null
        configChar = null
        val g = gattRef.getAndSet(null)
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
    }
}

