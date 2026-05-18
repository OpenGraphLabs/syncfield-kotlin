package io.opengraph.syncfield.insta360

import android.content.Context
import android.os.Looper
import com.arashivision.ble.OneBleIOCallbacks
import com.arashivision.camera.RequestOptions
import com.arashivision.onecamera.OneDriver
import com.arashivision.onecamera.OneDriverInfo
import com.arashivision.onecamera.camerarequest.TakePicture
import com.arashivision.onecamera.cameraresponse.OpenCameraWifiResp
import com.arashivision.onecamera.cameraresponse.StreamData
import com.arashivision.onecamera.cameraresponse.TakePictureResponse
import com.arashivision.onecamera.cameraresponse.TakePictureWithoutStorageResponse
import com.arashivision.onecamera.cameraresponse.VideoResult
import com.arashivision.camera.wifiproxy.IWifiProxyData
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adapter that mounts Insta360's native `OneDriver` on top of our
 * [Insta360BleProtocolSession].
 *
 * `OneDriver` is the JNI bridge the SDK uses for *all* high-level
 * camera commands (shutter, record, options, phone authorization,
 * heartbeat). It owns native protocol state and exposes a Java
 * surface that mirrors iOS's `INSCameraBasicCommands`:
 *
 * - `oneDriver.openCameraBle(callback)` activates BLE-proxy mode and
 *   registers an `OneBleIOCallbacks` whose `onWrite(byte[])` the
 *   native side calls whenever it has bytes to send to the camera.
 * - `oneDriver.setBleProxy(true)` flips the routing flag.
 * - `oneDriver.putData(bytes, true)` feeds incoming notify frames
 *   back into the native parser, which dispatches them as
 *   `OnNotificationListener.onDriverInfoNotify(what, err, obj)`.
 *
 * We wire those two ends to our protocol session:
 *   onWrite  → session.sendCommand
 *   session.notificationFlow → oneDriver.putData
 *
 * After this bridge attaches, calls like
 * `oneDriver.captureStillImage(...)` produce a real BLE write on the
 * GATT channel, the camera fires sights or records, and the result
 * notification comes back to us through `onDriverInfoNotify`.
 *
 * `InstaCameraManager` from the SDK is **not** used here — its own
 * `OneDriver` instance has no knowledge of our bypass connection and
 * would silently no-op all commands (error code -9999). This bridge
 * holds its own OneDriver tied to one BLE pair.
 */
internal class Insta360OneDriverBridge private constructor(
    private val context: Context,
    private val session: Insta360BleProtocolSession,
) {

    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val oneDriver: OneDriver = run {
        // Pre-load native libs manually so we can pinpoint exactly which
        // one fails (NativeLibsLoader inside OneDriver's <clinit> hides
        // the cause behind a NoClassDefFoundError).
        for (lib in listOf("c++_shared", "usb-1.0", "One")) {
            try {
                System.loadLibrary(lib)
                InstaLog.log(
                    InstaLogCategory.BLE,
                    event = "onedriver_native_lib_loaded",
                    fields = mapOf("lib" to lib),
                )
            } catch (e: UnsatisfiedLinkError) {
                InstaLog.log(
                    InstaLogCategory.BLE, level = InstaLogLevel.WARN,
                    event = "onedriver_native_lib_load_failed",
                    fields = mapOf("lib" to lib, "error" to (e.message ?: "")),
                )
                throw e
            } catch (e: Throwable) {
                InstaLog.log(
                    InstaLogCategory.BLE, level = InstaLogLevel.WARN,
                    event = "onedriver_native_lib_load_threw",
                    fields = mapOf(
                        "lib" to lib,
                        "error" to (e.message ?: e::class.java.simpleName),
                    ),
                )
            }
        }
        OneDriver(context.applicationContext, Looper.getMainLooper())
    }

    private val infoNotifications =
        MutableSharedFlow<InfoEvent>(replay = 0, extraBufferCapacity = 64)

    val infoEvents: SharedFlow<InfoEvent> = infoNotifications.asSharedFlow()

    @Volatile private var bleProxyActive: Boolean = false
    @Volatile private var closed: Boolean = false
    private var sessionNotifyForwarderJob: Job? = null

    /**
     * Outbound-write callback: native side asks us to write [data] to
     * the BLE characteristic. We route to the protocol session.
     */
    private val outboundCallback = object : OneBleIOCallbacks {
        override fun onWrite(data: ByteArray) {
            if (closed) return
            bridgeScope.launch {
                runCatching { session.sendCommand(data) }
                    .onFailure {
                        InstaLog.log(
                            InstaLogCategory.BLE, level = InstaLogLevel.WARN,
                            event = "onedriver_onWrite_failed",
                            fields = mapOf(
                                "bytes" to data.size,
                                "error" to (it.message ?: it::class.java.simpleName),
                            ),
                        )
                    }
            }
        }

        override fun onWifiProxyData(proxyData: ByteArray) {
            // BLE-proxy WiFi data — not used for our current use case
            // (we read camera AP creds out-of-band). Logged for visibility.
            InstaLog.log(
                InstaLogCategory.BLE, level = InstaLogLevel.DEBUG,
                event = "onedriver_wifi_proxy_data",
                fields = mapOf("bytes" to proxyData.size),
            )
        }
    }

    /**
     * Capture every notification the native parser surfaces. We
     * fan-out into [infoNotifications] so coroutine consumers (auth
     * flow, capture flow) can await specific `what` codes.
     */
    private val notificationListener = object : OneDriver.OnNotificationListener {
        override fun onDriverInfoNotify(what: Int, err: Int, obj: Any?) {
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "onedriver_info_notify",
                fields = mapOf("what" to what, "err" to err),
            )
            infoNotifications.tryEmit(InfoEvent(what, err, obj))
        }

        override fun onDriverRecordVideoStateNotify(state: Int, result: VideoResult?) {
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "onedriver_record_state",
                fields = mapOf("state" to state),
            )
            infoNotifications.tryEmit(InfoEvent(RECORD_VIDEO_STATE_WHAT, state, result))
        }

        override fun onDriverUsbState(state: Int, err: Int) = Unit
        override fun onDriverStillImageNotify(response: TakePictureResponse?) {
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "onedriver_still_image_notify",
                fields = mapOf("has_response" to (response != null)),
            )
            infoNotifications.tryEmit(InfoEvent(STILL_IMAGE_WHAT, 0, response))
        }

        override fun onDriverStillImageWithoutStorageNotify(
            response: TakePictureWithoutStorageResponse?,
        ) = Unit

        override fun onDriverTimelapseNotify(state: Int, result: VideoResult?) = Unit
        override fun onDriverStreamDataNotify(data: StreamData?) = Unit
        override fun onWifiProxyDataNotify(data: IWifiProxyData?) = Unit
    }

    init {
        oneDriver.setNotificationListener(notificationListener)
        val rc = oneDriver.openCameraBle(outboundCallback)
        if (rc != 0) {
            // Best-effort cleanup; throw so caller falls back gracefully.
            runCatching { oneDriver.close() }
            throw Insta360Error.CommandFailed(
                "OneDriver.openCameraBle failed rc=$rc"
            )
        }
        oneDriver.setBleProxy(true)
        bleProxyActive = true
        InstaLog.log(
            InstaLogCategory.BLE,
            event = "onedriver_ble_proxy_active",
            fields = mapOf("rc" to rc),
        )

        // Forward every notify frame the protocol session receives
        // into OneDriver's native parser. `bleProxy = true` tells the
        // parser the bytes came over BLE (vs WiFi).
        sessionNotifyForwarderJob = bridgeScope.launch {
            session.notificationFlow.collect { frame ->
                runCatching { oneDriver.putData(frame, true) }
                    .onFailure {
                        InstaLog.log(
                            InstaLogCategory.BLE, level = InstaLogLevel.WARN,
                            event = "onedriver_putData_failed",
                            fields = mapOf(
                                "bytes" to frame.size,
                                "error" to (it.message ?: it::class.java.simpleName),
                            ),
                        )
                    }
            }
        }
    }

    /**
     * Ask the camera whether [uniqueId] (a stable phone identifier) is
     * already authorized for this camera. Returns:
     *   - [OneDriverInfo.Response.Authenticate.AUTHORIZED] (0) — done,
     *     no LCD prompt needed.
     *   - [OneDriverInfo.Response.Authenticate.UNAUTHORIZED] (1) — the
     *     camera firmware has already begun showing the
     *     "Allow this app?" LCD prompt; call
     *     [awaitAuthorizationDecision] to wait for the user.
     *   - [OneDriverInfo.Response.Authenticate.SYSTEMBUSY] (2) — camera
     *     can't process auth right now.
     */
    suspend fun checkAuthorization(
        uniqueId: String,
        commandTimeoutMs: Long = 10_000L,
    ): Int {
        if (closed) throw Insta360Error.CommandFailed("OneDriverBridge closed")
        val ack = CompletableDeferred<Int>()
        val collector = bridgeScope.launch {
            infoNotifications
                .filter { it.what == OneDriverInfo.Response.InfoType.CHECK_AUTHORIZATION }
                .collect { event ->
                    if (!ack.isCompleted) ack.complete(event.err)
                }
        }
        try {
            val options = RequestOptions().apply { timeoutMs = commandTimeoutMs.toInt() }
            val requestId = oneDriver.checkAuthorization(uniqueId, options)
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "onedriver_check_authorization_sent",
                fields = mapOf("uniqueId" to uniqueId, "requestId" to requestId),
            )
            return withTimeout(commandTimeoutMs) { ack.await() }
        } finally {
            collector.cancel()
        }
    }

    /**
     * Suspend until the camera reports the user's decision via the
     * `NOTIFY_AUTHORIZATION` (80) frame. Returns one of:
     *   - [OneDriverInfo.Notification.Authorization.SUCCESS] (0)
     *   - [OneDriverInfo.Notification.Authorization.REJECT] (1)
     *   - [OneDriverInfo.Notification.Authorization.TIMEOUT] (2) — the
     *     camera firmware's own 30 s deadline expired
     *   - [OneDriverInfo.Notification.Authorization.SYSTEM_BUSY] (3)
     *
     * Throws on our own [timeoutMs] (defaults to slightly above the
     * camera's window so the firmware result reaches us first).
     */
    suspend fun awaitAuthorizationDecision(timeoutMs: Long = 35_000L): Int {
        if (closed) throw Insta360Error.CommandFailed("OneDriverBridge closed")
        return withTimeout(timeoutMs) {
            infoNotifications
                .filter { it.what == OneDriverInfo.Response.InfoType.NOTIFY_AUTHORIZATION }
                .first()
                .err
        }
    }

    /**
     * Cancel an in-flight authorization request (matches iOS's
     * `cancelCheckPhoneAuthorization`). Camera will drop the LCD
     * prompt and respond with a TIMEOUT/SYSTEM_BUSY notification.
     */
    fun cancelAuthorization() {
        if (closed) return
        runCatching {
            oneDriver.cancelRequestAuthorization(
                OneDriverInfo.Notification.AuthorizationOperationType.BLE_CONNECT
            )
        }.onFailure {
            InstaLog.log(
                InstaLogCategory.BLE, level = InstaLogLevel.WARN,
                event = "onedriver_cancel_auth_failed",
                fields = mapOf("error" to (it.message ?: it::class.java.simpleName)),
            )
        }
    }

    /**
     * Trigger a still-image capture (identify-photo / shutter).
     * Returns once the camera reports the capture finished (or fails).
     */
    /**
     * Start camera-storage record. Returns the request id from native;
     * caller can subscribe to [infoEvents] for `RECORD_VIDEO_STATE_WHAT`
     * if it needs to wait for the started/ended state.
     *
     * @param mode SDK record mode int (0 = normal record).
     */
    suspend fun startRecord(mode: Int = 0, timeoutMs: Long = 10_000L): Int {
        if (closed) throw Insta360Error.CommandFailed("OneDriverBridge closed")
        // Capture state notification — observed GO 3S Android OneDriver
        // semantics: 0 = recording started, 1 = stopped. Waiting for iOS-like
        // 1/2 values makes each command sit until timeout before the next
        // wrist camera can run.
        val ack = CompletableDeferred<Int>()
        val collector = bridgeScope.launch {
            infoNotifications
                .filter { it.what == RECORD_VIDEO_STATE_WHAT }
                .collect { event ->
                    if (Insta360RecordState.isStarted(event.err) && !ack.isCompleted) {
                        ack.complete(event.err)
                    }
                }
        }
        try {
            val rc = oneDriver.startRecordWithCameraStorage(mode)
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "onedriver_start_record_sent",
                fields = mapOf("mode" to mode, "rc" to rc),
            )
            // For "start" the camera responds quickly (LED + start chirp).
            // Cap how long we'll wait — caller may also race with timer.
            withTimeoutOrNull(timeoutMs) { ack.await() }
            return rc
        } finally {
            collector.cancel()
        }
    }

    /**
     * Stop record. Returns the camera-side video file URI when the SDK's
     * onDriverRecordVideoStateNotify carries it back in `VideoResult`.
     */
    suspend fun stopRecord(
        mode: Int = 0,
        extraMeta: ByteArray = ByteArray(0),
        timeoutMs: Long = 20_000L,
    ): String? = stopRecordAck(mode, extraMeta, timeoutMs).cameraFileURI

    internal suspend fun stopRecordAck(
        mode: Int = 0,
        extraMeta: ByteArray = ByteArray(0),
        timeoutMs: Long = 20_000L,
    ): StopRecordAck {
        if (closed) throw Insta360Error.CommandFailed("OneDriverBridge closed")
        val ack = CompletableDeferred<StopRecordAck>()
        val collector = bridgeScope.launch {
            infoNotifications
                .filter { it.what == RECORD_VIDEO_STATE_WHAT }
                .collect { event ->
                    if (!Insta360RecordState.isStopped(event.err)) return@collect
                    val result = event.obj as? VideoResult
                    val uri = result?.video?.uri?.takeIf { it.isNotBlank() }
                    if (!ack.isCompleted) ack.complete(StopRecordAck(uri))
                }
        }
        try {
            oneDriver.stopRecordWithCameraStorage(mode, extraMeta)
            InstaLog.log(InstaLogCategory.BLE, event = "onedriver_stop_record_sent")
            return withTimeoutOrNull(timeoutMs) { ack.await() }
                ?: throw Insta360Error.CommandFailed("stop record timed out waiting for stopped state")
        } finally {
            collector.cancel()
        }
    }

    suspend fun captureStillImage(timeoutMs: Long = 15_000L): TakePictureResponse? {
        if (closed) throw Insta360Error.CommandFailed("OneDriverBridge closed")
        val ack = CompletableDeferred<TakePictureResponse?>()
        val collector = bridgeScope.launch {
            infoNotifications
                .filter { it.what == STILL_IMAGE_WHAT }
                .collect { event ->
                    if (!ack.isCompleted) {
                        ack.complete(event.obj as? TakePictureResponse)
                    }
                }
        }
        try {
            val tp = TakePicture()
            oneDriver.captureStillImage(tp)
            InstaLog.log(InstaLogCategory.BLE, event = "onedriver_capture_still_image_sent")
            return withTimeout(timeoutMs) { ack.await() }
        } finally {
            collector.cancel()
        }
    }

    /**
     * Ask the camera to bring up its own Wi-Fi AP before Android requests
     * the `GO 3S ... .OSC` network. This mirrors iOS'
     * `enableWiFiForDownload`; without this preflight Android races the
     * system network request against an AP that is not yet advertising and
     * `ConnectivityManager` reports `onUnavailable`.
     */
    suspend fun enableWifiForDownload(timeoutMs: Long = 8_000L) {
        if (closed) throw Insta360Error.CommandFailed("OneDriverBridge closed")
        val ack = CompletableDeferred<Int>()
        val collector = bridgeScope.launch {
            infoNotifications
                .filter {
                    it.what == OneDriverInfo.Response.InfoType.OPEN_CAMERA_WIFI ||
                        it.what == OneDriverInfo.Response.InfoType.CAM_WIFI_START
                }
                .collect { event ->
                    val responseCode = (event.obj as? OpenCameraWifiResp)?.errorCode
                    val code = responseCode ?: event.err
                    if (!ack.isCompleted) ack.complete(code)
                }
        }
        try {
            val requestId = oneDriver.openCameraWifi(0)
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "onedriver_open_camera_wifi_sent",
                fields = mapOf("requestId" to requestId, "mode" to 0),
            )
            if (requestId < 0) {
                throw Insta360Error.CommandFailed("open camera wifi returned requestId=$requestId")
            }
            val code = withTimeoutOrNull(timeoutMs) { ack.await() }
            when {
                code == null -> {
                    // Some firmware turns the AP on but does not deliver the
                    // response frame over BLE. Treat this as Swift does: a
                    // latency hint, not a hard precondition.
                    InstaLog.log(
                        InstaLogCategory.BLE,
                        level = InstaLogLevel.WARN,
                        event = "onedriver_open_camera_wifi_timeout_continue",
                        fields = mapOf("timeout_ms" to timeoutMs),
                    )
                }
                code == 0 -> {
                    InstaLog.log(
                        InstaLogCategory.BLE,
                        event = "onedriver_open_camera_wifi_ok",
                    )
                }
                else -> {
                    throw Insta360Error.CommandFailed("open camera wifi failed code=$code")
                }
            }
        } finally {
            collector.cancel()
        }
    }

    fun sendHeartBeat() {
        if (closed) return
        runCatching { oneDriver.sendHeartBeat() }
            .onFailure {
                InstaLog.log(
                    InstaLogCategory.BLE, level = InstaLogLevel.WARN,
                    event = "onedriver_heartbeat_failed",
                    fields = mapOf("error" to (it.message ?: it::class.java.simpleName)),
                )
            }
    }

    fun close() {
        if (closed) return
        closed = true
        sessionNotifyForwarderJob?.cancel()
        if (bleProxyActive) {
            runCatching { oneDriver.setBleProxy(false) }
            runCatching { oneDriver.closeBle() }
            bleProxyActive = false
        }
        runCatching { oneDriver.close() }
        bridgeScope.coroutineContext[Job]?.cancel()
        InstaLog.log(InstaLogCategory.BLE, event = "onedriver_bridge_closed")
    }

    data class InfoEvent(val what: Int, val err: Int, val obj: Any?)
    internal data class StopRecordAck(val cameraFileURI: String?)

    companion object {
        // Pseudo-`what` codes for callbacks that don't carry one. We
        // pick values well above the SDK's namespace to avoid clashes.
        private const val STILL_IMAGE_WHAT: Int = 1_000_001
        private const val RECORD_VIDEO_STATE_WHAT: Int = 1_000_002

        fun attach(
            context: Context,
            session: Insta360BleProtocolSession,
        ): Insta360OneDriverBridge = Insta360OneDriverBridge(context, session)
    }
}

internal object Insta360RecordState {
    private const val STARTED = 0
    private const val STOPPED = 1

    fun isStarted(state: Int): Boolean = state == STARTED
    fun isStopped(state: Int): Boolean = state == STOPPED
}
