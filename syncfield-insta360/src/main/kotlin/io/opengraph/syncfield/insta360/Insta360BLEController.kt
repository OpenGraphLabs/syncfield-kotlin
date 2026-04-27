package io.opengraph.syncfield.insta360

import android.content.Context
import io.opengraph.syncfield.SessionClock

/**
 * BLE controller for a single Insta360 Go 3S camera.
 *
 * Lifecycle mirrors `Insta360BLEController.swift`:
 *
 * 1. [pair] — short-scan + GATT-connect to the first Go camera in range.
 * 2. [startRemoteRecording] — send the SDK's `startCapture` BLE command,
 *    record host-monotonic ACK time.
 * 3. [stopRemoteRecording] — send `stopCapture`, capture the
 *    camera-side video file URI from the SDK's completion callback.
 * 4. [wifiCredentials] — read the camera's AP SSID + passphrase over BLE.
 * 5. [unpair] — disconnect.
 *
 * Production behaviour requires Insta360's `INSCameraSDK` Android AAR
 * — without it, every call throws [Insta360Error.FrameworkNotLinked].
 * The SDK calls live inside `production()` blocks so the structure of
 * this class stays identical to the iOS controller; host apps that
 * link the AAR fill in the SDK invocations using
 * [Insta360OneSDKBridge].
 */
class Insta360BLEController(private val context: Context) {

    /** Host-monotonic nanoseconds of the most recent `startCapture` ACK. */
    @Volatile var lastStartAckNs: Long = 0L
        private set

    suspend fun pair() {
        if (!Insta360OneSDKBridge.available) throw Insta360Error.FrameworkNotLinked
        // TODO[host]: scan + connect through the OneSDK BluetoothManager.
        //   val mgr = staticCall("...InstaBluetoothManager", "getInstance")
        //   mgr.scanCameras { device, _, _ -> ... }
        //   mgr.connect(device) { error -> ... }
        throw Insta360Error.FrameworkNotLinked
    }

    suspend fun unpair() {
        if (!Insta360OneSDKBridge.available) return
        // TODO[host]: disconnect via OneSDK.
    }

    /**
     * Send a BLE start-capture command and return the host-monotonic
     * nanosecond timestamp at the moment the ACK landed.
     */
    suspend fun startRemoteRecording(clock: SessionClock): Long {
        if (!Insta360OneSDKBridge.available) throw Insta360Error.FrameworkNotLinked
        // TODO[host]: cmd.startCapture(...) { error -> resume }
        throw Insta360Error.FrameworkNotLinked
    }

    /**
     * Send a BLE stop-capture command and return the camera-side video
     * URI from the SDK's completion callback.
     */
    suspend fun stopRemoteRecording(): String {
        if (!Insta360OneSDKBridge.available) throw Insta360Error.FrameworkNotLinked
        // TODO[host]: cmd.stopCapture(...) { error, videoInfo -> resume(videoInfo.uri) }
        throw Insta360Error.FrameworkNotLinked
    }

    /**
     * Retrieve the camera AP's WiFi SSID and passphrase. Strategy
     * mirrors the Swift implementation:
     * 1. Read cached `device.wifiInfo`.
     * 2. Fall back to `getOptionsWithTypes` over BLE.
     * 3. Final fallback: derive SSID from BLE name + default `88888888`.
     */
    suspend fun wifiCredentials(): Pair<String, String> {
        if (!Insta360OneSDKBridge.available) throw Insta360Error.FrameworkNotLinked
        // TODO[host]: read device.wifiInfo / getOptionsWithTypes.
        throw Insta360Error.FrameworkNotLinked
    }
}
