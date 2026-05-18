package io.opengraph.syncfield.insta360

import android.content.Context
import com.arashivision.sdkcamera.camera.InstaCameraManager
import com.arashivision.sdkcamera.camera.callback.ICameraChangedCallback
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

data class Insta360StopCaptureResult(
    val cameraFileURI: String?,
    val confirmedStopped: Boolean,
    val attempts: Int,
    val diagnostic: String? = null,
    val stopWallClockMs: Long? = null,
)

internal data class Insta360ResolvedWifiCredentials(
    val ssid: String,
    val password: String,
    val ssidSource: String,
    val passwordSource: String,
)

private data class CachedInsta360WifiCredentials(
    val deviceKey: String,
    val credentials: Insta360ResolvedWifiCredentials,
)

internal const val INSTA360_DEFAULT_WIFI_PASSWORD = "88888888"

internal fun deriveInsta360Ssid(deviceName: String?, stableId: String?): String {
    val base = deviceName.normalizedSsidOrNull()
        ?: stableId.normalizedSsidOrNull()
        ?: return ""
    return if (base.endsWith(".OSC")) base else "$base.OSC"
}

internal fun resolveInsta360WifiCredentials(
    deviceName: String?,
    stableId: String?,
    sdkSsid: String?,
    sdkPassword: String?,
    preferSdkSsid: Boolean = false,
): Insta360ResolvedWifiCredentials {
    val derivedSsid = deriveInsta360Ssid(deviceName, stableId).takeIf { it.isNotBlank() }
    val normalizedSdkSsid = sdkSsid.normalizedSsidOrNull()
    val ssid = when {
        preferSdkSsid && normalizedSdkSsid != null -> normalizedSdkSsid
        derivedSsid != null -> derivedSsid
        normalizedSdkSsid != null -> normalizedSdkSsid
        else -> ""
    }
    val ssidSource = when {
        preferSdkSsid && normalizedSdkSsid != null -> "sdk"
        derivedSsid != null -> "derived"
        normalizedSdkSsid != null -> "sdk"
        else -> "missing"
    }
    val sdkSsidMatchesDevice = normalizedSdkSsid == null || normalizedSdkSsid == ssid || derivedSsid == null
    val normalizedSdkPassword = sdkPassword?.trim()?.takeIf { it.isNotBlank() }
    val password = if (sdkSsidMatchesDevice) {
        normalizedSdkPassword ?: INSTA360_DEFAULT_WIFI_PASSWORD
    } else {
        INSTA360_DEFAULT_WIFI_PASSWORD
    }
    val passwordSource = if (sdkSsidMatchesDevice && normalizedSdkPassword != null) "sdk" else "default"
    return Insta360ResolvedWifiCredentials(
        ssid = ssid,
        password = password,
        ssidSource = ssidSource,
        passwordSource = passwordSource,
    )
}

private fun String?.normalizedSsidOrNull(): String? =
    this?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }

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
     * Native command bridge mounted on top of [protocolSession]. This
     * is where high-level commands (phone authorization, shutter,
     * record start/stop, options I/O) actually execute — mirrors
     * iOS's `INSCameraBasicCommands`. `null` until the protocol
     * session is established.
     */
    @Volatile internal var oneDriverBridge: Insta360OneDriverBridge? = null
        private set

    /**
     * Snapshot of the camera AP credentials captured while the BLE command
     * channel is freshly paired. iOS keeps the same cache so ingest can avoid
     * querying options during the radio handoff to the camera AP.
     */
    @Volatile private var cachedWifiCredentials: CachedInsta360WifiCredentials? = null

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
        prefetchWifiCredentials(device)
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
        oneDriverBridge?.runCatching { close() }
        oneDriverBridge = null
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
     * Send a BLE start-record command and return the host-monotonic
     * nanosecond timestamp at the moment the ACK landed. Mirrors
     * iOS `Insta360BLEController.startRemoteRecording`.
     *
     * Routed via [Insta360OneDriverBridge.startRecord] so the native
     * SDK builds the packet; the live BLE session built during pair()
     * is reused — we do NOT re-pair, which would tear down the active
     * GATT channel and starve subsequent writes.
     */
    suspend fun startRemoteRecording(clock: SessionClock): Long {
        setup()
        val device = requireDevice()
        return commandQueue.runDeviceCommand(
            commandId(device),
            timeoutMs = 30_000L,
            retries = 1,
            sdkCritical = false,
        ) {
            val bridge = oneDriverBridge
                ?: throw Insta360Error.NotPaired
            bridge.startRecord(mode = 0)
            val ackNs = clock.nowMonotonicNs()
            lastStartAckNs = ackNs
            ackNs
        }
    }

    /**
     * Stop record. Returns the camera-side video file URI captured
     * from the SDK's `onDriverRecordVideoStateNotify` payload.
     */
    suspend fun stopRemoteRecording(): String {
        val result = stopRemoteRecordingReliably()
        return result.cameraFileURI?.takeIf { it.isNotBlank() }
            ?: throw Insta360Error.CommandFailed(
                result.diagnostic ?: "stop record confirmed stop but returned no file uri"
            )
    }

    suspend fun stopRemoteRecordingReliably(): Insta360StopCaptureResult {
        setup()
        val device = requireDevice()
        val stopWallClockMs = System.currentTimeMillis()
        return commandQueue.runDeviceCommand(
            commandId(device),
            timeoutMs = 45_000L,
            retries = 1,
            sdkCritical = false,
        ) {
            val bridge = oneDriverBridge
                ?: throw Insta360Error.NotPaired
            val ack = bridge.stopRecordAck(mode = 0)
            val uri = ack.cameraFileURI?.takeIf { it.isNotBlank() }
            Insta360StopCaptureResult(
                cameraFileURI = uri,
                confirmedStopped = true,
                attempts = 1,
                diagnostic = if (uri == null) "stop record acked without uri" else null,
                stopWallClockMs = stopWallClockMs,
            )
        }
    }

    /**
     * Retrieve the camera AP's WiFi SSID and passphrase without changing the
     * live BLE/OneDriver session. Upload calls this immediately after
     * [enableWiFiForDownload]; reconnecting here closes the session that just
     * enabled the AP and can make Android miss the camera network.
     */
    suspend fun wifiCredentials(): Pair<String, String> {
        setup()
        val device = requireDevice()
        return commandQueue.runDeviceCommand(
            commandId(device),
            timeoutMs = 25_000L,
            retries = 0,
            sdkCritical = false,
        ) {
            val resolved = resolveWifiCredentialsForDevice(
                device = device,
                fetchOptions = true,
                preferCached = true,
            )
            val ssid = resolved.ssid
            if (ssid.isBlank()) throw Insta360Error.WifiCredentialsUnavailable
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "wifi_credentials_resolved",
                fields = mapOf(
                    "ssid" to ssid,
                    "ssid_source" to resolved.ssidSource,
                    "password_source" to resolved.passwordSource,
                    "cached" to cachedWifiCredentialsMatches(device, resolved),
                    "preserved_session" to true,
                ),
            )
            ssid to resolved.password
        }
    }

    private suspend fun prefetchWifiCredentials(device: BleDevice) {
        commandQueue.runDeviceCommand(
            commandId(device),
            timeoutMs = 5_000L,
            retries = 0,
            sdkCritical = false,
        ) {
            runCatching {
                resolveWifiCredentialsForDevice(
                    device = device,
                    fetchOptions = true,
                    preferCached = false,
                )
            }.onSuccess { resolved ->
                InstaLog.log(
                    InstaLogCategory.BLE,
                    event = "wifi_credentials_prefetch_ok",
                    fields = mapOf(
                        "ssid" to resolved.ssid,
                        "ssid_source" to resolved.ssidSource,
                        "password_source" to resolved.passwordSource,
                    ),
                )
            }.onFailure { error ->
                InstaLog.log(
                    InstaLogCategory.BLE,
                    level = InstaLogLevel.WARN,
                    event = "wifi_credentials_prefetch_failed",
                    fields = mapOf("error" to (error.message ?: error::class.java.simpleName)),
                )
            }
        }
    }

    private suspend fun resolveWifiCredentialsForDevice(
        device: BleDevice,
        fetchOptions: Boolean,
        preferCached: Boolean,
    ): Insta360ResolvedWifiCredentials {
        val cached = cachedWifiCredentialsFor(device)
        if (preferCached && cached != null) {
            if (!fetchOptions || cached.ssidSource == "sdk") {
                InstaLog.log(
                    InstaLogCategory.BLE,
                    event = "wifi_credentials_cache_hit",
                    fields = mapOf(
                        "ssid" to cached.ssid,
                        "ssid_source" to cached.ssidSource,
                        "password_source" to cached.passwordSource,
                    ),
                )
                return cached
            }
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "wifi_credentials_cache_refresh_needed",
                fields = mapOf(
                    "ssid" to cached.ssid,
                    "ssid_source" to cached.ssidSource,
                    "password_source" to cached.passwordSource,
                ),
            )
        }

        val stableId = Insta360OneSDKBridge.stableId(device)
        if (fetchOptions) {
            runCatching {
                oneDriverBridge?.fetchWifiCredentials(
                    deviceName = device.name,
                    stableId = stableId,
                    timeoutMs = 8_000L,
                )
            }
                .onSuccess { resolved ->
                    if (resolved != null && resolved.ssid.isNotBlank()) {
                        cachedWifiCredentials = CachedInsta360WifiCredentials(
                            deviceKey = wifiCredentialCacheKey(device),
                            credentials = resolved,
                        )
                        InstaLog.log(
                            InstaLogCategory.BLE,
                            event = "wifi_credentials_onedriver_options_ok",
                            fields = mapOf(
                                "ssid" to resolved.ssid,
                                "ssid_source" to resolved.ssidSource,
                                "password_source" to resolved.passwordSource,
                            ),
                        )
                        return resolved
                    }
                    InstaLog.log(
                        InstaLogCategory.BLE,
                        level = InstaLogLevel.WARN,
                        event = "wifi_credentials_onedriver_options_empty",
                    )
                }
                .onFailure { error ->
                    InstaLog.log(
                        InstaLogCategory.BLE,
                        level = InstaLogLevel.WARN,
                        event = "wifi_credentials_onedriver_options_failed",
                        fields = mapOf("error" to (error.message ?: error::class.java.simpleName)),
                    )
                }
            if (preferCached && cached != null) {
                InstaLog.log(
                    InstaLogCategory.BLE,
                    level = InstaLogLevel.WARN,
                    event = "wifi_credentials_cache_fallback",
                    fields = mapOf(
                        "ssid" to cached.ssid,
                        "ssid_source" to cached.ssidSource,
                        "password_source" to cached.passwordSource,
                    ),
                )
                return cached
            }
        }

        val wifi = runCatching { manager.wifiInfo }.getOrNull()
        val resolved = resolveInsta360WifiCredentials(
            deviceName = device.name,
            stableId = stableId,
            sdkSsid = wifi?.ssid,
            sdkPassword = wifi?.pwd,
            preferSdkSsid = false,
        )
        if (resolved.ssid.isNotBlank()) {
            cachedWifiCredentials = CachedInsta360WifiCredentials(
                deviceKey = wifiCredentialCacheKey(device),
                credentials = resolved,
            )
        }
        return resolved
    }

    private fun cachedWifiCredentialsFor(device: BleDevice): Insta360ResolvedWifiCredentials? {
        return cachedWifiCredentials?.takeIf { cached ->
            cached.deviceKey.isNotBlank() && cached.deviceKey == wifiCredentialCacheKey(device)
        }?.credentials
    }

    private fun cachedWifiCredentialsMatches(
        device: BleDevice,
        credentials: Insta360ResolvedWifiCredentials,
    ): Boolean = cachedWifiCredentialsFor(device)?.ssid == credentials.ssid

    private fun wifiCredentialCacheKey(device: BleDevice): String =
        Insta360OneSDKBridge.stableId(device)
            .normalizedSsidOrNull()
            ?: device.name.normalizedSsidOrNull()
            ?: ""

    /**
     * Bring up the camera's Wi-Fi AP before Android asks the OS to join the
     * `*.OSC` network. BLE still owns the control plane here; the actual
     * phone Wi-Fi join/download happens later in [Insta360WiFiDownloader].
     */
    suspend fun enableWiFiForDownload() {
        setup()
        val device = requireDevice()
        commandQueue.runDeviceCommand(
            commandId(device),
            timeoutMs = 15_000L,
            retries = 1,
            sdkCritical = false,
        ) {
            if (oneDriverBridge == null || protocolSession == null) {
                connectDeviceWithRetry(device)
            }
            val bridge = oneDriverBridge
                ?: throw Insta360Error.NotPaired
            bridge.enableWifiForDownload()
        }
    }

    /**
     * Trigger a still-image capture on the camera (identify-photo).
     * Routed through [Insta360OneDriverBridge.captureStillImage] which
     * writes the SDK's native shutter command on our protocol session —
     * `InstaCameraManager.startNormalCapture()` does not work for us
     * because the SDK's own OneDriver has no awareness of our bypass
     * connection (returns error code -9999).
     */
    suspend fun triggerIdentifyPhoto() {
        setup()
        val device = requireDevice()
        commandQueue.runDeviceCommand(commandId(device), timeoutMs = 30_000L, retries = 1) {
            val bridge = oneDriverBridge
                ?: throw Insta360Error.NotPaired
            bridge.captureStillImage(timeoutMs = 15_000L)
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
     * Phone authorization on Android — mirrors iOS's
     * `Insta360BLEController.performPhoneAuthorization` semantically.
     *
     * Sequence (matches iOS):
     *  1. Call `OneDriver.checkAuthorization(deviceId)` over our BLE
     *     channel. Camera replies via `onDriverInfoNotify(what=78, err=state)`
     *     where state is `Response.Authenticate.AUTHORIZED|UNAUTHORIZED|SYSTEMBUSY`.
     *  2. If `AUTHORIZED`: already trusted, return [PhoneAuthorizationResult.Authorized]
     *     immediately — no LCD prompt needed.
     *  3. If `UNAUTHORIZED`: the camera firmware has begun showing
     *     "이 앱의 접근을 허용할까요?" on its LCD. Invoke
     *     [onCameraPromptStarted] so the host app can render its own
     *     "ActionPod 을 확인하고 승인 눌러주세요" overlay with a 30 s timer.
     *     Then suspend on `onDriverInfoNotify(what=80, err=Notification.Authorization.*)`
     *     which carries the user's decision.
     *  4. If `SYSTEMBUSY`: surface as `Rejected` (caller may retry later).
     *
     * @param uniqueId Stable, host-specific identifier for the phone.
     *   Use [Insta360PhoneAuthDeviceId.stableId] which mirrors iOS's
     *   `INSConnectionUtils.authorizationId()` (Settings.Secure.ANDROID_ID
     *   on cold start, persisted in SharedPreferences thereafter).
     */
    suspend fun requestPhoneAuthorization(
        uniqueId: String,
        timeoutSeconds: Long = 30,
        onCameraPromptStarted: (() -> Unit)? = null,
    ): PhoneAuthorizationResult {
        InstaLog.log(InstaLogCategory.BRIDGE, event = "phone_auth_required")
        setup()
        // The bridge needs an active protocol session. If we don't have
        // one yet, pair() opens the BLE channel + handshake; that itself
        // does NOT show the LCD prompt — only the explicit
        // checkAuthorization call below does.
        if (oneDriverBridge == null || protocolSession == null) {
            pair()
        }
        val bridge = oneDriverBridge
            ?: throw Insta360Error.CommandFailed("OneDriver bridge unavailable after pair")

        return try {
            val initialState = bridge.checkAuthorization(uniqueId)
            InstaLog.log(
                InstaLogCategory.BRIDGE,
                event = "phone_auth_initial_state",
                fields = mapOf("state" to initialState),
            )
            when (initialState) {
                OneDriverInfoConstants.AUTHENTICATE_AUTHORIZED -> {
                    InstaLog.log(
                        InstaLogCategory.BRIDGE,
                        event = "phone_auth_result",
                        fields = mapOf("result" to "already_authorized"),
                    )
                    PhoneAuthorizationResult.Authorized
                }
                OneDriverInfoConstants.AUTHENTICATE_UNAUTHORIZED -> {
                    // Camera LCD is now showing "Allow this phone?" — let the
                    // host app render its overlay/timer.
                    onCameraPromptStarted?.invoke()
                    InstaLog.log(InstaLogCategory.BRIDGE, event = "phone_auth_prompt_started")
                    val decision = bridge.awaitAuthorizationDecision(
                        timeoutMs = timeoutSeconds * 1_000L + 5_000L,
                    )
                    InstaLog.log(
                        InstaLogCategory.BRIDGE,
                        event = "phone_auth_decision",
                        fields = mapOf("decision" to decision),
                    )
                    when (decision) {
                        OneDriverInfoConstants.AUTH_RESULT_SUCCESS -> PhoneAuthorizationResult.Authorized
                        OneDriverInfoConstants.AUTH_RESULT_REJECT -> PhoneAuthorizationResult.Rejected
                        OneDriverInfoConstants.AUTH_RESULT_TIMEOUT -> PhoneAuthorizationResult.TimedOut
                        OneDriverInfoConstants.AUTH_RESULT_SYSTEM_BUSY -> PhoneAuthorizationResult.Rejected
                        else -> PhoneAuthorizationResult.Rejected
                    }
                }
                OneDriverInfoConstants.AUTHENTICATE_SYSTEMBUSY -> {
                    InstaLog.log(
                        InstaLogCategory.BRIDGE, level = InstaLogLevel.WARN,
                        event = "phone_auth_result",
                        fields = mapOf("result" to "system_busy"),
                    )
                    PhoneAuthorizationResult.Rejected
                }
                else -> {
                    InstaLog.log(
                        InstaLogCategory.BRIDGE, level = InstaLogLevel.WARN,
                        event = "phone_auth_unknown_state",
                        fields = mapOf("state" to initialState),
                    )
                    PhoneAuthorizationResult.Rejected
                }
            }
        } catch (e: TimeoutCancellationException) {
            InstaLog.log(
                InstaLogCategory.BRIDGE, level = InstaLogLevel.WARN,
                event = "phone_auth_result",
                fields = mapOf("result" to "timeout"),
            )
            PhoneAuthorizationResult.TimedOut
        } catch (e: Throwable) {
            InstaLog.log(
                InstaLogCategory.BRIDGE, level = InstaLogLevel.WARN,
                event = "phone_auth_result",
                fields = mapOf("result" to "failure", "error" to (e.message ?: e::class.java.simpleName)),
            )
            PhoneAuthorizationResult.Rejected
        }
    }

    /**
     * Cancel an in-flight [requestPhoneAuthorization]. Mirrors iOS's
     * `cancelPendingPhoneAuthorization` — calls
     * `OneDriver.cancelRequestAuthorization(BLE_CONNECT)` which drops
     * the camera-side LCD prompt. Caller's [requestPhoneAuthorization]
     * coroutine sees the resulting TIMEOUT/SYSTEM_BUSY notification.
     */
    suspend fun cancelPendingPhoneAuthorization() {
        oneDriverBridge?.cancelAuthorization()
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
            oneDriverBridge?.runCatching { close() }
            protocolSession = session
            // Mount the JNI command bridge on top so high-level commands
            // (auth, shutter, record) can flow through native packet
            // construction over our BLE channel.
            oneDriverBridge = runCatching {
                Insta360OneDriverBridge.attach(context, session)
            }.getOrElse { t ->
                // Walk the cause chain so the *root* native-loader / linker
                // failure is visible — top-level message is often just the
                // class name (ClassNotFoundException semantics).
                val causes = generateSequence(t as Throwable?) { it.cause }
                    .toList()
                    .joinToString(" <- ") { c ->
                        "${c::class.java.simpleName}(${c.message ?: ""})"
                    }
                android.util.Log.e(
                    "INSTA360_BLE",
                    "onedriver_bridge_attach_failed: $causes",
                    t,
                )
                InstaLog.log(
                    InstaLogCategory.BLE, level = InstaLogLevel.WARN,
                    event = "onedriver_bridge_attach_failed",
                    fields = mapOf(
                        "top" to (t.message ?: t::class.java.simpleName),
                        "causes" to causes,
                    ),
                )
                null
            }
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

    private fun commandId(device: BleDevice?): String =
        device?.let { Insta360OneSDKBridge.stableId(it) }
            ?: connectedDeviceUuid
            ?: lastKnownDeviceUUID
            ?: "unpaired-${System.identityHashCode(this)}"
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
