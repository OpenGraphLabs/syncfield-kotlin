package io.opengraph.syncfield.insta360

import android.content.Context
import com.arashivision.sdkcamera.camera.InstaCameraManager
import com.arashivision.sdkcamera.camera.callback.ICameraChangedCallback
import com.arashivision.sdkcamera.camera.callback.ICameraOperateCallback
import com.arashivision.sdkcamera.camera.callback.ICaptureStatusListener
import com.arashivision.sdkcamera.camera.callback.IScanBleListener
import com.arashivision.sdkcamera.camera.model.CaptureMode
import com.clj.fastble.data.BleDevice
import io.opengraph.syncfield.SessionClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * BLE controller for a single Insta360 Go 3S camera.
 *
 * Lifecycle mirrors `Insta360BLEController.swift`:
 *
 * 1. [pair] - short-scan + GATT-connect to the first Go camera in range.
 * 2. [startRemoteRecording] - send the SDK's `startCapture` BLE command,
 *    record host-monotonic ACK time.
 * 3. [stopRemoteRecording] - send `stopCapture`, capture the
 *    camera-side video file URI from the SDK's completion callback.
 * 4. [wifiCredentials] - read the camera's AP SSID + passphrase over BLE.
 * 5. [unpair] - disconnect.
 *
 * Android's public SDK exposes `InstaCameraManager` as a process-wide
 * singleton. SyncField therefore serializes every BLE command through a
 * global mutex. For multi-wrist sessions, start/stop commands are sent to
 * each camera sequentially and each stream stores the host-side ACK time
 * returned by its own command completion.
 */
class Insta360BLEController(
    private val context: Context,
    initialDevice: BleDevice? = null,
) {

    /** Host-monotonic nanoseconds of the most recent `startCapture` ACK. */
    @Volatile var lastStartAckNs: Long = 0L
        private set

    /** Stable BLE UUID/name of the connected camera, when the OneSDK exposes it. */
    @Volatile var connectedDeviceUuid: String? = null
        private set

    @Volatile var connectedDeviceName: String? = null
        private set

    private var bleDevice: BleDevice? = initialDevice

    suspend fun pair() {
        setup()
        val device = bleDevice ?: scanFirstGoCamera().also { bleDevice = it }
        sdkMutex.withLock {
            connectDeviceWithRetry(device)
            setConnectedIdentity(device)
        }
    }

    suspend fun unpair() {
        if (!Insta360OneSDKBridge.available) return
        setup()
        sdkMutex.withLock {
            runCatching { manager.disconnectBle() }
        }
        connectedDeviceUuid = null
        connectedDeviceName = null
        bleDevice = null
    }

    /**
     * Send a BLE start-capture command and return the host-monotonic
     * nanosecond timestamp at the moment the ACK landed.
     */
    suspend fun startRemoteRecording(clock: SessionClock): Long {
        setup()
        val device = requireDevice()
        return sdkMutex.withLock {
            connectDeviceWithRetry(device)
            ensureNormalRecordMode()
            if (!manager.isSdCardEnabled) {
                throw Insta360Error.CommandFailed("SD card is not available")
            }

            val ack = CompletableDeferred<Long>()
            val listener = object : ICaptureStatusListener {
                override fun onCaptureWorking() {
                    if (!ack.isCompleted) ack.complete(clock.nowMonotonicNs())
                }

                override fun onCaptureFinish(paths: Array<out String>?) = Unit

                override fun onCaptureError(code: Int) {
                    if (!ack.isCompleted) {
                        ack.completeExceptionally(
                            Insta360Error.CommandFailed("start capture error code=$code")
                        )
                    }
                }
            }
            manager.setCaptureStatusListener(listener)
            try {
                manager.startNormalRecord()
                withTimeout(10_000) { ack.await() }.also { lastStartAckNs = it }
            } finally {
                manager.setCaptureStatusListener(null)
            }
        }
    }

    /**
     * Send a BLE stop-capture command and return the camera-side video
     * URI from the SDK's completion callback.
     */
    suspend fun stopRemoteRecording(): String {
        setup()
        val device = requireDevice()
        return sdkMutex.withLock {
            connectDeviceWithRetry(device)
            val file = CompletableDeferred<String>()
            val listener = object : ICaptureStatusListener {
                override fun onCaptureFinish(paths: Array<out String>?) {
                    val uri = paths?.firstOrNull { it.isNotBlank() }
                    if (uri.isNullOrBlank()) {
                        file.completeExceptionally(
                            Insta360Error.CommandFailed("stop capture returned no file path")
                        )
                    } else {
                        file.complete(uri)
                    }
                }

                override fun onCaptureError(code: Int) {
                    if (!file.isCompleted) {
                        file.completeExceptionally(
                            Insta360Error.CommandFailed("stop capture error code=$code")
                        )
                    }
                }
            }
            manager.setCaptureStatusListener(listener)
            try {
                manager.stopNormalRecord()
                withTimeout(20_000) { file.await() }
            } finally {
                manager.setCaptureStatusListener(null)
            }
        }
    }

    /**
     * Retrieve the camera AP's WiFi SSID and passphrase. Strategy
     * mirrors the Swift implementation:
     * 1. Read cached `device.wifiInfo`.
     * 2. Fall back to `getOptionsWithTypes` over BLE.
     * 3. Final fallback: derive SSID from BLE name + default `88888888`.
     */
    suspend fun wifiCredentials(): Pair<String, String> {
        setup()
        val device = requireDevice()
        return sdkMutex.withLock {
            connectDeviceWithRetry(device)
            runCatching { fetchCameraOptions() }
            val wifi = manager.wifiInfo
            val ssid = wifi?.ssid?.takeIf { it.isNotBlank() }
                ?: derivedSsid(device)
            val password = wifi?.pwd?.takeIf { it.isNotBlank() } ?: DEFAULT_WIFI_PASSWORD
            if (ssid.isBlank()) throw Insta360Error.WifiCredentialsUnavailable
            ssid to password
        }
    }

    suspend fun triggerIdentifyPhoto() {
        setup()
        val device = requireDevice()
        sdkMutex.withLock {
            connectDeviceWithRetry(device)
            val result = CompletableDeferred<Unit>()
            val listener = object : ICaptureStatusListener {
                override fun onCaptureFinish(paths: Array<out String>?) {
                    if (!result.isCompleted) result.complete(Unit)
                }

                override fun onCaptureError(code: Int) {
                    if (!result.isCompleted) {
                        result.completeExceptionally(
                            Insta360Error.IdentifyPhotoFailed("capture error code=$code")
                        )
                    }
                }
            }
            manager.setCaptureStatusListener(listener)
            try {
                ensureCaptureMode(CaptureMode.CAPTURE_NORMAL)
                manager.startNormalCapture()
                withTimeout(15_000) { result.await() }
            } finally {
                manager.setCaptureStatusListener(null)
            }
        }
    }

    internal fun adoptDevice(device: BleDevice) {
        bleDevice = device
        setConnectedIdentity(device)
    }

    private fun setup() {
        Insta360OneSDKBridge.setup(context)
    }

    private val manager: InstaCameraManager
        get() = Insta360OneSDKBridge.manager

    private fun requireDevice(): BleDevice =
        bleDevice ?: throw Insta360Error.NotPaired

    private suspend fun scanFirstGoCamera(): BleDevice {
        val found = CompletableDeferred<BleDevice>()
        manager.setScanBleListener(object : IScanBleListener {
            override fun onScanStartSuccess() = Unit

            override fun onScanStartFail() {
                if (!found.isCompleted) {
                    found.completeExceptionally(Insta360Error.CommandFailed("BLE scan failed"))
                }
            }

            override fun onScanning(bleDevice: BleDevice) {
                if (Insta360BluetoothHub.shouldEmitDevice(bleDevice.name) && !found.isCompleted) {
                    found.complete(bleDevice)
                    manager.stopBleScan()
                }
            }

            override fun onScanFinish(list: List<BleDevice>) {
                val device = list.firstOrNull { Insta360BluetoothHub.shouldEmitDevice(it.name) }
                if (device != null && !found.isCompleted) {
                    found.complete(device)
                } else if (!found.isCompleted) {
                    found.completeExceptionally(
                        Insta360Error.CommandFailed("no Insta360 Go camera discovered")
                    )
                }
            }
        })
        try {
            manager.startBleScan()
            return withTimeout(15_000) { found.await() }
        } finally {
            runCatching { manager.stopBleScan() }
            manager.setScanBleListener(null)
        }
    }

    private suspend fun connectDeviceWithRetry(device: BleDevice) {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                connectDevice(device)
                delay(1_000)
                return
            } catch (t: Throwable) {
                last = t
                runCatching { manager.disconnectBle() }
                if (attempt < 2) delay((attempt + 1) * 1_000L)
            }
        }
        throw last ?: Insta360Error.CommandFailed("BLE connect failed")
    }

    private suspend fun connectDevice(device: BleDevice) {
        val connected = CompletableDeferred<Unit>()
        val callback = object : ICameraChangedCallback {
            override fun onCameraStatusChanged(enabled: Boolean, connectType: Int) {
                if (enabled && connectType == InstaCameraManager.CONNECT_TYPE_BLE && !connected.isCompleted) {
                    connected.complete(Unit)
                }
            }

            override fun onCameraConnectError(errorCode: Int) {
                if (!connected.isCompleted) {
                    connected.completeExceptionally(
                        Insta360Error.CommandFailed("BLE connect error code=$errorCode")
                    )
                }
            }
        }
        manager.registerCameraChangedCallback(callback)
        try {
            runCatching { manager.disconnectBle() }
            manager.connectBle(device)
            withTimeout(15_000) { connected.await() }
            setConnectedIdentity(device)
        } finally {
            manager.unregisterCameraChangedCallback(callback)
        }
    }

    private suspend fun fetchCameraOptions() {
        val done = CompletableDeferred<Unit>()
        manager.fetchCameraOptions(object : ICameraOperateCallback {
            override fun onSuccessful() {
                if (!done.isCompleted) done.complete(Unit)
            }

            override fun onFailed() {
                if (!done.isCompleted) {
                    done.completeExceptionally(Insta360Error.CommandFailed("fetch camera options failed"))
                }
            }

            override fun onCameraConnectError() {
                if (!done.isCompleted) {
                    done.completeExceptionally(Insta360Error.CommandFailed("camera connect error"))
                }
            }
        })
        withTimeout(10_000) { done.await() }
    }

    private suspend fun ensureNormalRecordMode() {
        ensureCaptureMode(CaptureMode.RECORD_NORMAL)
    }

    private suspend fun ensureCaptureMode(mode: CaptureMode) {
        val done = CompletableDeferred<Unit>()
        manager.setCaptureMode(mode) { code ->
            if (code == 0) {
                done.complete(Unit)
            } else {
                done.completeExceptionally(
                    Insta360Error.CommandFailed("set capture mode $mode failed code=$code")
                )
            }
        }
        withTimeout(10_000) { done.await() }
    }

    private fun setConnectedIdentity(device: BleDevice) {
        connectedDeviceUuid = Insta360OneSDKBridge.stableId(device)
        connectedDeviceName = device.name
    }

    private fun derivedSsid(device: BleDevice): String {
        val name = device.name?.takeIf { it.isNotBlank() }
            ?: Insta360OneSDKBridge.stableId(device)
        return if (name.endsWith(".OSC")) name else "$name.OSC"
    }

    companion object {
        private const val DEFAULT_WIFI_PASSWORD = "88888888"
        internal val sdkMutex = Mutex()
    }
}
