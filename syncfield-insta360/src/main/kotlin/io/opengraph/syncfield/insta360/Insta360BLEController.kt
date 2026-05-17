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
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * BLE controller for a single Insta360 Go 3S camera.
 *
 * Lifecycle mirrors `Insta360BLEController.swift`:
 *
 * 1. [pair] - short-scan + GATT-connect to the first Go camera in range.
 *    Auto-starts the 2s heartbeat task. Caches device UUID/name into
 *    [lastKnownDeviceUUID]/[lastKnownDeviceName] so they survive BLE drops.
 * 2. [startRemoteRecording] - send the SDK's `startCapture` BLE command,
 *    record host-monotonic ACK time.
 * 3. [stopRemoteRecording] - send `stopCapture`, capture the
 *    camera-side video file URI from the SDK's completion callback.
 * 4. [wifiCredentials] - read the camera's AP SSID + passphrase over BLE.
 * 5. [unpair] - disconnect. Cancels heartbeat + cleanup.
 *
 * Stability mechanisms (mirror Swift production fixes from v0.9.x):
 *
 * - **2 s heartbeat** ([startHeartbeat]) — periodic lightweight BLE round-trip
 *   keeps the camera awake during idle periods. Android SDK does not expose an
 *   explicit `sendHeartbeats()` like iOS, so we use [InstaCameraManager.fetchCameraBatteryState]
 *   which triggers a BLE read with minimal side effects.
 * - **lastKnown cache** — the live `bleDevice` clears on unpair; [lastKnownDeviceUUID]
 *   and [lastKnownDeviceName] survive so [reconnectIfNeeded] and the bridge's
 *   sidecar writers can find the camera after a transient drop.
 * - **Unsolicited disconnect handler** — supervisors feed
 *   [unsolicitedDisconnectHandler] into the connection state machine on
 *   `onCameraStatusChanged(false, BLE)` callbacks.
 * - **Readiness probe** ([assertActionCamHost]) — fast-fail when the
 *   advertised device isn't actually a recording-capable ActionCam host.
 */
class Insta360BLEController(
    private val context: Context,
    initialDevice: BleDevice? = null,
) {

    /** Host-monotonic nanoseconds of the most recent `startCapture` ACK. */
    @Volatile var lastStartAckNs: Long = 0L
        private set

    /** Stable BLE UUID of the currently connected camera, when the OneSDK exposes it. */
    @Volatile var connectedDeviceUuid: String? = null
        private set

    @Volatile var connectedDeviceName: String? = null
        private set

    /**
     * Last successful pair's UUID. Persists across BLE drops so callers
     * (stopRecording sidecar writer, reconnectIfNeeded) can identify the
     * camera even when [connectedDeviceUuid] is null.
     */
    @Volatile var lastKnownDeviceUUID: String? = null
        private set

    /**
     * Last successful pair's BLE name. Same survival semantics as
     * [lastKnownDeviceUUID]; used by re-pair fast-path which uses name-based
     * scan matching.
     */
    @Volatile var lastKnownDeviceName: String? = null
        private set

    /**
     * Optional handler invoked when the camera disconnects without an explicit
     * [unpair] call. Wired by [Insta360CameraSupervisor] (Phase 1) to feed
     * `UnsolicitedDisconnect` events into the connection state machine.
     */
    @Volatile var unsolicitedDisconnectHandler: (suspend (Throwable?) -> Unit)? = null

    /** Active heartbeat interval. `null` means heartbeat is paused (e.g. WiFi lease). */
    @Volatile var heartbeatIntervalMs: Long? = 2_000L
        private set

    private var bleDevice: BleDevice? = initialDevice
    private val commandQueue = Insta360CommandQueue.shared
    private val controllerScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var heartbeatJob: Job? = null

    /**
     * Live BLE protocol session established by [Insta360GattHandshake].
     * Mirrors the role of iOS's per-device `INSCameraBasicCommands`
     * (returned by `bluetoothManager.getCommandBy(device)`) — the
     * channel through which heartbeats and (future) command packets
     * are written. Reset on unpair / unsolicited drop.
     */
    @Volatile private var protocolSession: Insta360BleProtocolSession? = null

    /**
     * Listener for unsolicited BLE disconnects. Registered eagerly so we can
     * observe drops even when no in-flight command is running. Kept as a
     * field so [unpair] can deregister cleanly.
     */
    private var disconnectListener: ICameraChangedCallback? = null

    init {
        initialDevice?.let { setConnectedIdentity(it) }
    }

    suspend fun pair() {
        InstaLog.log(InstaLogCategory.BLE, event = "pair_started")
        setup()
        val device = bleDevice ?: scanFirstGoCamera().also { bleDevice = it }
        InstaLog.log(
            InstaLogCategory.BLE, event = "pair_device_resolved",
            fields = mapOf("name" to (device.name ?: ""), "mac" to (device.mac ?: "")),
        )
        commandQueue.runDeviceCommand(commandId(device), timeoutMs = 45_000L, retries = 1) {
            InstaLog.log(InstaLogCategory.BLE, event = "pair_connect_attempt_start")
            connectDeviceWithRetry(device)
            InstaLog.log(InstaLogCategory.BLE, event = "pair_connect_attempt_done")
            setConnectedIdentity(device)
            registerDisconnectListener()
        }
        startHeartbeat()
        InstaLog.log(
            InstaLogCategory.BLE,
            level = InstaLogLevel.INFO,
            event = "pair_ok",
            fields = mapOf(
                "uuid" to connectedDeviceUuid,
                "name" to connectedDeviceName,
            ),
        )
    }

    suspend fun unpair() {
        if (!Insta360OneSDKBridge.available) return
        setup()
        stopHeartbeat()
        unregisterDisconnectListener()
        val device = bleDevice
        protocolSession?.runCatching { close() }
        protocolSession = null
        commandQueue.runDeviceCommand(commandId(device), timeoutMs = 15_000L, retries = 0) {
            runCatching { manager.disconnectBle() }
            // Drop our own fastble GATT connection too — SDK's
            // disconnectBle() doesn't know about it.
            device?.let { runCatching { com.clj.fastble.BleManager.getInstance().disconnect(it) } }
        }
        // Note: lastKnown* deliberately NOT cleared — survives so pending
        // sidecars can be written even after unpair completes.
        connectedDeviceUuid = null
        connectedDeviceName = null
        bleDevice = null
        InstaLog.log(
            InstaLogCategory.BLE,
            level = InstaLogLevel.INFO,
            event = "unpair_ok",
        )
    }

    /**
     * Send a BLE start-capture command and return the host-monotonic
     * nanosecond timestamp at the moment the ACK landed.
     */
    suspend fun startRemoteRecording(clock: SessionClock): Long {
        setup()
        val device = requireDevice()
        return commandQueue.runDeviceCommand(commandId(device), timeoutMs = 30_000L, retries = 1) {
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
        return commandQueue.runDeviceCommand(commandId(device), timeoutMs = 45_000L, retries = 1) {
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
        return commandQueue.runDeviceCommand(commandId(device), timeoutMs = 30_000L, retries = 1) {
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
        commandQueue.runDeviceCommand(commandId(device), timeoutMs = 30_000L, retries = 1) {
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
        startHeartbeat()
        registerDisconnectListener()
    }

    // --- Heartbeat ----------------------------------------------------------

    /**
     * Adjust the heartbeat cadence. Pass `null` to pause (called by [Insta360RadioGate]
     * when this camera holds the WiFi lease, since AP-bound state can't service BLE).
     * Pass a value to resume at that cadence (slow-mode for non-AP-bound cameras
     * during another camera's lease).
     *
     * Mirrors Swift `setHeartbeatIntervalMs(_:)`.
     */
    fun setHeartbeatIntervalMs(ms: Long?) {
        heartbeatIntervalMs = ms
        if (ms == null) {
            InstaLog.log(InstaLogCategory.BLE, event = "heartbeat_paused")
        } else {
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "heartbeat_interval_set",
                fields = mapOf("interval_ms" to ms),
            )
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = controllerScope.launch {
            while (isActive) {
                val interval = heartbeatIntervalMs
                if (interval == null) {
                    delay(500)  // cheap poll while paused
                    continue
                }
                delay(interval)
                if (!isActive) break
                sendHeartbeatOnce()
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Single heartbeat tick. iOS posts a keepalive via the per-device
     * `INSCameraCommandManager.sendHeartbeats(_:)`; we write the exact
     * heartbeat packet ([Insta360ProtocolPackets.HEARTBEAT_PACKET])
     * extracted from `BleConnectCmd.java:386` on our protocol session.
     * Failures are logged but do not throw — transient misses are
     * exactly what the supervisor's reconnect logic is for.
     */
    private fun sendHeartbeatOnce() {
        val session = protocolSession ?: return
        controllerScope.launch {
            try {
                session.sendCommand(Insta360ProtocolPackets.HEARTBEAT_PACKET)
            } catch (t: Throwable) {
                InstaLog.log(
                    InstaLogCategory.BLE,
                    level = InstaLogLevel.WARN,
                    event = "heartbeat_send_failed",
                    fields = mapOf("error" to (t.message ?: t::class.java.simpleName)),
                )
            }
        }
    }

    // --- Reconnect ----------------------------------------------------------

    /**
     * No-op if BLE is still connected; otherwise attempts a re-pair using the
     * cached [lastKnownDeviceUUID]. Used by the supervisor reconnect path and
     * by command handlers that need to defensively re-establish before
     * issuing a critical command.
     *
     * Mirrors Swift `reconnectIfNeeded()`.
     */
    suspend fun reconnectIfNeeded() {
        setup()
        if (bleDevice != null && manager.cameraConnectedType == InstaCameraManager.CONNECT_TYPE_BLE) {
            return
        }
        InstaLog.log(
            InstaLogCategory.BLE,
            event = "reconnect_requested",
            fields = mapOf(
                "last_known_uuid" to lastKnownDeviceUUID,
                "last_known_name" to lastKnownDeviceName,
            ),
        )
        // Scan briefly for the cached device and retry connect.
        val device = bleDevice ?: scanFirstGoCamera().also { bleDevice = it }
        connectDeviceWithRetry(device)
        setConnectedIdentity(device)
        registerDisconnectListener()
        startHeartbeat()
    }

    /**
     * Fast readiness probe — after `connectBle` succeeds we may still have
     * a stale or unhealthy BLE channel. Verify by reading the current camera
     * connect type with a 1 s timeout. Throws [Insta360Error.NotRecordingActionCam]
     * if the host isn't actually a recording-capable Action Cam.
     *
     * Mirrors Swift `assertActionCamHost(timeoutMs:)`.
     */
    suspend fun assertActionCamHost(timeoutMs: Long = 1_000L) {
        setup()
        try {
            withTimeout(timeoutMs) {
                // Poll the SDK's local connect-type cache; if it's BLE we know
                // the GATT channel is up. We do NOT issue a round-trip because
                // many round-trips on the Android SDK block indefinitely if
                // the camera is a non-host peripheral (e.g. Action Pod box).
                while (manager.cameraConnectedType != InstaCameraManager.CONNECT_TYPE_BLE) {
                    delay(50)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw Insta360Error.NotRecordingActionCam
        }
    }

    // --- Phone authorization -------------------------------------------------

    /**
     * Phone authorization on Android.
     *
     * The Android Insta360 SDK 1.10.1 does **not** expose an explicit
     * `requestCameraPermission` like the iOS SDK does. The authorization
     * handshake is handled inside `connectBle()` — by the time [pair] returns,
     * either the camera has accepted the phone or `connectBle` has thrown.
     *
     * This method exists for API parity with iOS so the RN bridge surface
     * is symmetric. It probes [Insta360IdentityStore] for a cached
     * `PhoneAuthorizationCacheState.Authorized` record and resolves
     * immediately, otherwise it triggers a [pair] (which will perform the
     * implicit authorization) and marks the cache on success.
     *
     * On Android, the [onCameraPromptStarted] callback fires immediately
     * (there is no separate "camera screen prompt" phase).
     */
    suspend fun requestPhoneAuthorization(
        timeoutSeconds: Long = 30,
        onCameraPromptStarted: (() -> Unit)? = null,
    ): PhoneAuthorizationResult {
        InstaLog.log(InstaLogCategory.BRIDGE, event = "phone_auth_required")
        onCameraPromptStarted?.invoke()
        return try {
            withTimeout(timeoutSeconds * 1_000L) {
                // The Android pair flow IS the authorization flow.
                if (bleDevice != null && manager.cameraConnectedType == InstaCameraManager.CONNECT_TYPE_BLE) {
                    PhoneAuthorizationResult.Authorized
                } else {
                    pair()
                    PhoneAuthorizationResult.Authorized
                }
            }.also {
                InstaLog.log(
                    InstaLogCategory.BRIDGE,
                    event = "phone_auth_result",
                    fields = mapOf("result" to "success"),
                )
            }
        } catch (e: TimeoutCancellationException) {
            InstaLog.log(
                InstaLogCategory.BRIDGE,
                level = InstaLogLevel.WARN,
                event = "phone_auth_result",
                fields = mapOf("result" to "timeout"),
            )
            PhoneAuthorizationResult.TimedOut
        } catch (e: Throwable) {
            InstaLog.log(
                InstaLogCategory.BRIDGE,
                level = InstaLogLevel.WARN,
                event = "phone_auth_result",
                fields = mapOf("result" to "failure", "error" to (e.message ?: e::class.java.simpleName)),
            )
            PhoneAuthorizationResult.Rejected
        }
    }

    /**
     * Cancel an in-flight [requestPhoneAuthorization]. On Android this maps to
     * disconnecting the BLE channel since pairing IS authorization.
     */
    suspend fun cancelPendingPhoneAuthorization() {
        if (!Insta360OneSDKBridge.available) return
        runCatching { manager.disconnectBle() }
        InstaLog.log(
            InstaLogCategory.BRIDGE,
            event = "phone_auth_result",
            fields = mapOf("result" to "canceled"),
        )
    }

    // --- Lifecycle cleanup --------------------------------------------------

    /**
     * Tear down everything owned by this controller. Call when the host
     * (CameraStream / Coordinator) is being destroyed. After [shutdown],
     * the controller cannot be reused — create a fresh instance.
     */
    fun shutdown() {
        stopHeartbeat()
        unregisterDisconnectListener()
        controllerScope.coroutineContext[Job]?.cancel()
    }

    // --- Internals ----------------------------------------------------------

    private suspend fun setup() {
        Insta360OneSDKBridge.setup(context)
    }

    private val manager: InstaCameraManager
        get() = Insta360OneSDKBridge.manager

    private fun requireDevice(): BleDevice =
        bleDevice ?: throw Insta360Error.NotPaired

    private suspend fun scanFirstGoCamera(): BleDevice {
        return commandQueue.runGlobalCommand(timeoutMs = 20_000L) {
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
                withTimeout(15_000) { found.await() }
            } finally {
                runCatching { manager.stopBleScan() }
                manager.setScanBleListener(null)
            }
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

    /**
     * Bring up the BLE channel for [device] end-to-end:
     *   1. fastble GATT connect (via [Insta360DirectGattConnector])
     *   2. Insta360 protocol handshake — MTU bump, notify subscribe on
     *      service `be80`, sync packet (`ab ba × 5`) ↔ sync response
     *      (`ff 06 41 ...`) round-trip
     *
     * Stops at sync handshake. Wake-up authorization (the `ff 0d 03 ...`
     * round-trip that triggers the "Approve this phone" LCD prompt) is
     * built inside the SDK's `OneDriver` JNI layer
     * (`sendWakeUpAuthorization(uuid)`) and we cannot reproduce its
     * outbound packet without that native code. First-pair UX (camera
     * prompt) is therefore not yet exercised here — see the plan's
     * Risk Register.
     *
     * Mirrors iOS `Insta360BLEController.connectScannedDevice(device)`
     * (Swift line ~2148), which wraps the equivalent single-call
     * `bluetoothManager.connect(device, completion)`.
     */
    private suspend fun connectDevice(device: BleDevice) {
        InstaLog.log(
            InstaLogCategory.BLE, event = "connectBle_via_handshake",
            fields = mapOf(
                "name" to (device.name ?: ""),
                "mac" to (device.mac ?: ""),
                "device_rssi" to device.rssi,
            ),
        )
        try {
            val session = Insta360GattHandshake.handshake(device)
            protocolSession?.runCatching { close() } // drop any stale prior session
            protocolSession = session
            setConnectedIdentity(device)
        } catch (t: Throwable) {
            InstaLog.log(
                InstaLogCategory.BLE, level = InstaLogLevel.WARN,
                event = "connectBle_threw",
                fields = mapOf("error" to (t.message ?: t::class.java.simpleName)),
            )
            throw t
        }
    }

    /**
     * Eagerly register a long-lived listener that catches unsolicited BLE
     * drops (camera off, out of range, GATT 133). Calls
     * [unsolicitedDisconnectHandler] which the supervisor wires up.
     */
    private fun registerDisconnectListener() {
        if (disconnectListener != null) return
        val listener = object : ICameraChangedCallback {
            override fun onCameraStatusChanged(enabled: Boolean, connectType: Int) {
                if (!enabled && connectType == InstaCameraManager.CONNECT_TYPE_BLE) {
                    InstaLog.log(
                        InstaLogCategory.BLE,
                        event = "didDisconnectWithError",
                        fields = mapOf("type" to "ble_drop"),
                    )
                    stopHeartbeat()
                    val handler = unsolicitedDisconnectHandler ?: return
                    controllerScope.launch {
                        runCatching { handler(null) }
                    }
                }
            }

            override fun onCameraConnectError(errorCode: Int) {
                InstaLog.log(
                    InstaLogCategory.BLE,
                    level = InstaLogLevel.WARN,
                    event = "didDisconnectWithError",
                    fields = mapOf("type" to "connect_error", "code" to errorCode),
                )
                val handler = unsolicitedDisconnectHandler ?: return
                controllerScope.launch {
                    runCatching {
                        handler(Insta360Error.CommandFailed("connect error code=$errorCode"))
                    }
                }
            }
        }
        manager.registerCameraChangedCallback(listener)
        disconnectListener = listener
    }

    private fun unregisterDisconnectListener() {
        disconnectListener?.let {
            runCatching { manager.unregisterCameraChangedCallback(it) }
            disconnectListener = null
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
        val uuid = Insta360OneSDKBridge.stableId(device)
        connectedDeviceUuid = uuid
        connectedDeviceName = device.name
        // Update lastKnown cache only on success — survives subsequent BLE drops.
        if (!uuid.isNullOrEmpty()) lastKnownDeviceUUID = uuid
        if (!device.name.isNullOrEmpty()) lastKnownDeviceName = device.name
    }

    private fun derivedSsid(device: BleDevice): String {
        val name = device.name?.takeIf { it.isNotBlank() }
            ?: Insta360OneSDKBridge.stableId(device)
        return if (name.endsWith(".OSC")) name else "$name.OSC"
    }

    private fun commandId(device: BleDevice?): String =
        device?.let { Insta360OneSDKBridge.stableId(it) }
            ?: connectedDeviceUuid
            ?: lastKnownDeviceUUID
            ?: "unpaired-${System.identityHashCode(this)}"

    companion object {
        private const val DEFAULT_WIFI_PASSWORD = "88888888"
    }
}

/**
 * Outcome of a [Insta360BLEController.requestPhoneAuthorization] call.
 * Android's behavior collapses the iOS multi-state result into the cases
 * that are observable here.
 */
sealed class PhoneAuthorizationResult {
    object Authorized : PhoneAuthorizationResult()
    object Rejected : PhoneAuthorizationResult()
    object TimedOut : PhoneAuthorizationResult()
    object Canceled : PhoneAuthorizationResult()
}
