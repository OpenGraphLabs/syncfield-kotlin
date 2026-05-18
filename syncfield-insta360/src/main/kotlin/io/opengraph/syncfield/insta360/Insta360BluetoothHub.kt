package io.opengraph.syncfield.insta360

import android.content.Context
import com.arashivision.sdkcamera.camera.callback.IScanBleListener
import com.clj.fastble.data.BleDevice
import io.opengraph.syncfield.HealthEvent
import io.opengraph.syncfield.SessionOrchestrator
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

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

    private val stateMutex = Mutex()
    private val discoveries = MutableSharedFlow<DiscoveredInsta360>(
        replay = 0,
        extraBufferCapacity = 32,
    )
    private val scannedDevices = ConcurrentHashMap<String, BleDevice>()
    private val pairedControllers = ConcurrentHashMap<String, Insta360BLEController>()
    private val pairLocks = ConcurrentHashMap<String, Mutex>()
    @Volatile private var scanActive = false
    @Volatile private var nativeScanner: Insta360NativeBleScanner? = null

    /**
     * Filter for Insta360 Go-family BLE advertisements.
     *
     * Insta360 GO 3S advertises with names like `"GO 3S 12345"`, `"GO3S_12345"`,
     * `"Insta360 GO 3S"`. Older models surface as `"GO"`, `"GO2"`, `"GO 2"`.
     * Accept any name that matches the GO family OR the broader "insta" brand,
     * since GO 3S firmware variants sometimes drop the "GO" prefix entirely.
     */
    fun shouldEmitDevice(name: String?): Boolean {
        val n = name?.lowercase()?.trim() ?: return false
        if (n.isEmpty()) return false
        return n.contains("go") || n.contains("insta")
    }

    fun streamId(forRole: String): String = "cam_wrist_$forRole"

    suspend fun startScan(context: Context): Flow<DiscoveredInsta360> = stateMutex.withLock {
        if (scanActive) {
            InstaLog.log(InstaLogCategory.SCAN, event = "scan_already_active_reused")
            return@withLock discoveries.asSharedFlow()
        }
        Insta360CommandQueue.shared.runGlobalCommand(timeoutMs = 10_000L) {
            Insta360OneSDKBridge.setup(context)
            val manager = Insta360OneSDKBridge.manager
            manager.setScanBleListener(object : IScanBleListener {
                override fun onScanStartSuccess() {
                    InstaLog.log(InstaLogCategory.SCAN, event = "scan_started")
                }

                override fun onScanStartFail() {
                    scanActive = false
                    InstaLog.log(
                        InstaLogCategory.SCAN, level = InstaLogLevel.WARN,
                        event = "scan_start_failed",
                    )
                }

                override fun onScanning(bleDevice: BleDevice) {
                    // Temporarily INFO so we can confirm whether the SDK scan
                    // pipeline (which depends on IConfiguration filters) is
                    // emitting GO3S devices, independent of the native scan
                    // fallback. If `sdk_scan_callback` lines never appear,
                    // the support patch isn't deep enough.
                    InstaLog.log(
                        InstaLogCategory.SCAN,
                        level = InstaLogLevel.INFO,
                        event = "sdk_scan_callback",
                        fields = mapOf(
                            "name" to (bleDevice.name ?: "null"),
                            "mac" to (bleDevice.mac ?: "null"),
                            "rssi" to bleDevice.rssi,
                            "accepted" to shouldEmitDevice(bleDevice.name),
                        ),
                    )
                    handleScanHit(bleDevice)
                }

                override fun onScanFinish(list: List<BleDevice>) {
                    InstaLog.log(
                        InstaLogCategory.SCAN, event = "scan_finished",
                        fields = mapOf("total" to list.size),
                    )
                    list.forEach { handleScanHit(it) }
                }
            })
            scanActive = true
            manager.startBleScan()
            InstaLog.log(InstaLogCategory.SCAN, event = "scan_requested")

            // Parallel native scanner — SDK's internal filter drops every
            // advertisement until per-camera support config is loaded
            // (chicken-and-egg). Native scanner emits independently into
            // the same `discoveries` SharedFlow via handleScanHit.
            nativeScanner?.stop()
            nativeScanner = Insta360NativeBleScanner(context) { device ->
                handleScanHit(device)
            }.also { it.start() }
        }
        discoveries.asSharedFlow()
    }

    suspend fun stopScan() = stateMutex.withLock {
        if (!Insta360OneSDKBridge.available) return@withLock
        Insta360CommandQueue.shared.runGlobalCommand(timeoutMs = 10_000L) {
            runCatching { Insta360OneSDKBridge.manager.stopBleScan() }
            runCatching { Insta360OneSDKBridge.manager.setScanBleListener(null) }
        }
        nativeScanner?.stop()
        nativeScanner = null
        scanActive = false
    }

    suspend fun pair(context: Context, uuid: String): Insta360BLEController {
        pairedControllers[uuid]?.let { return it }
        val lock = pairLocks.getOrPut(uuid) { Mutex() }
        return lock.withLock {
            pairedControllers[uuid]?.let { return@withLock it }
            val device = scannedDevices[uuid] ?: throw Insta360Error.DeviceNotDiscovered(uuid)
            val controller = Insta360BLEController(context, device)
            controller.pair()
            pairedControllers[uuid] = controller
            controller
        }
    }

    suspend fun triggerIdentifyPhoto(context: Context, uuid: String) {
        pair(context, uuid).triggerIdentifyPhoto()
    }

    suspend fun unpair(uuid: String) {
        val controller = pairedControllers.remove(uuid)
        controller?.unpair()
    }

    suspend fun unpairAll() {
        val controllers = pairedControllers.values.toList()
        pairedControllers.clear()
        controllers.forEach { runCatching { it.unpair() } }
    }

    suspend fun pairedUUIDs(): Set<String> = pairedControllers.keys.toSet()

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
        val emitted = discoveries.tryEmit(
            DiscoveredInsta360(
                uuid = uuid,
                name = device.name.orEmpty(),
                rssi = device.rssi,
            )
        )
        InstaLog.log(
            InstaLogCategory.SCAN, event = "scan_hit",
            fields = mapOf(
                "uuid" to uuid,
                "name" to device.name.orEmpty(),
                "rssi" to device.rssi,
                "emitted" to emitted,
            ),
        )
    }
}
