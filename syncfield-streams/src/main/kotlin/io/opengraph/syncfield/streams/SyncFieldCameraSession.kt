package io.opengraph.syncfield.streams

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordination point between the host's session bridge (which creates
 * the [AndroidCameraStream]) and any preview view that needs to bind
 * to it.
 *
 * Mirrors the iOS `SyncFieldBridgeModule.currentCaptureSession` +
 * `cameraSessionAttachedNotification` pair. On iOS the bridge posts a
 * notification when the active `AVCaptureSession` changes; on Android
 * we publish the active stream as a [StateFlow] so the preview view
 * can collect, rebind, and stay in sync without the host wiring two
 * components together manually.
 */
object SyncFieldCameraSession {

    private val _activeStream = MutableStateFlow<AndroidCameraStream?>(null)

    /** Observable handle to whichever camera stream is currently live. */
    val activeStream: StateFlow<AndroidCameraStream?> = _activeStream.asStateFlow()

    /**
     * Set or clear the active camera stream. Call from the bridge
     * after `connect()` and again on `disconnect()` (with `null`).
     */
    fun setActive(stream: AndroidCameraStream?) {
        _activeStream.value = stream
    }
}
