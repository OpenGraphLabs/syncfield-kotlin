package io.opengraph.syncfield.insta360

import android.content.Context
import com.arashivision.sdkcamera.camera.callback.IScanBleListener
import com.clj.fastble.data.BleDevice
import io.opengraph.syncfield.HealthEvent
import io.opengraph.syncfield.SessionOrchestrator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Snapshot of a BLE-discovered Insta360 camera. */
data class DiscoveredInsta360(
    val uuid: String,
    val name: String,
    val rssi: Int,
)

/**
 * Process-wide coordinator for Insta360 discovery and role adoption.
 *
 * The Android SDK's `InstaCameraManager` owns one active command route,
 * so this hub keeps the scan/pair registry while individual controllers
 * reconnect to their cached BLE device for each serialized command.
 */
object Insta360BluetoothHub {

    private val mutex = Mutex()
    private val discoveries = MutableSharedFlow<DiscoveredInsta360>(
        replay = 0,
        extraBufferCapacity = 32,
    )
    private val scannedDevices: MutableMap<String, BleDevice> = mutableMapOf()
    private val pairedControllers: MutableMap<String, Insta360BLEController> = mutableMapOf()
    private var scanActive = false

    /** Pure helper: accept Go-family cameras, matching iOS behaviour. */
    fun shouldEmitDevice(name: String?): Boolean =
        name?.lowercase()?.contains("go") == true

    fun streamId(forRole: String): String = "cam_wrist_$forRole"

    suspend fun startScan(context: Context): Flow<DiscoveredInsta360> = mutex.withLock {
        if (scanActive) throw Insta360Error.ScanAlreadyActive
        Insta360OneSDKBridge.setup(context)
        val manager = Insta360OneSDKBridge.manager
        manager.setScanBleListener(object : IScanBleListener {
            override fun onScanStartSuccess() = Unit

            override fun onScanStartFail() {
                scanActive = false
            }

            override fun onScanning(bleDevice: BleDevice) {
                handleScanHit(bleDevice)
            }

            override fun onScanFinish(list: List<BleDevice>) {
                list.forEach { handleScanHit(it) }
            }
        })
        scanActive = true
        manager.startBleScan()
        discoveries.asSharedFlow()
    }

    suspend fun stopScan() = mutex.withLock {
        if (!Insta360OneSDKBridge.available) return@withLock
        runCatching { Insta360OneSDKBridge.manager.stopBleScan() }
        runCatching { Insta360OneSDKBridge.manager.setScanBleListener(null) }
        scanActive = false
    }

    suspend fun pair(context: Context, uuid: String): Insta360BLEController {
        pairedControllers[uuid]?.let { return it }
        val device = mutex.withLock {
            scannedDevices[uuid] ?: throw Insta360Error.DeviceNotDiscovered(uuid)
        }
        val controller = Insta360BLEController(context, device)
        controller.pair()
        mutex.withLock {
            pairedControllers[uuid] = controller
        }
        return controller
    }

    suspend fun triggerIdentifyPhoto(context: Context, uuid: String) {
        pair(context, uuid).triggerIdentifyPhoto()
    }

    suspend fun unpair(uuid: String) {
        val controller = mutex.withLock { pairedControllers.remove(uuid) }
        controller?.unpair()
    }

    suspend fun unpairAll() {
        val controllers = mutex.withLock {
            val list = pairedControllers.values.toList()
            pairedControllers.clear()
            list
        }
        controllers.forEach { runCatching { it.unpair() } }
    }

    suspend fun pairedUUIDs(): Set<String> = mutex.withLock {
        pairedControllers.keys.toSet()
    }

    suspend fun controller(context: Context, uuid: String): Insta360BLEController =
        pairedControllers[uuid] ?: pair(context, uuid)

    suspend fun adoptAsWristStream(
        context: Context,
        uuid: String,
        role: String,
        session: SessionOrchestrator,
        onHealthEvent: (HealthEvent) -> Unit,
    ): Insta360CameraStream {
        val controller = pair(context, uuid)
        val id = streamId(forRole = role)
        val stream = Insta360CameraStream(
            context = context,
            streamId = id,
            controller = controller,
            externallyConnected = true,
        )
        stream.adoptHealthHandler(onHealthEvent)
        session.add(stream)
        return stream
    }

    private fun handleScanHit(device: BleDevice) {
        if (!shouldEmitDevice(device.name)) return
        val uuid = Insta360OneSDKBridge.stableId(device)
        scannedDevices[uuid] = device
        discoveries.tryEmit(
            DiscoveredInsta360(
                uuid = uuid,
                name = device.name.orEmpty(),
                rssi = device.rssi,
            )
        )
    }
}
