package io.opengraph.syncfield.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import io.opengraph.syncfield.streams.AndroidCameraStream
import io.opengraph.syncfield.streams.SyncFieldCameraSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Drop-in preview view for [AndroidCameraStream] — Android counterpart
 * of the iOS `SyncFieldPreviewView`.
 *
 * The view auto-binds to whichever camera stream the bridge module is
 * currently driving via [SyncFieldCameraSession]. Drop it anywhere in
 * the layout and the preview lights up as soon as `connect()` opens
 * the camera; black until then. This mirrors the iOS contract where
 * the view manager observes
 * `SyncFieldBridgeModule.cameraSessionAttachedNotification` and
 * rebinds without any host-app glue code.
 *
 * Manual binding via [bind] is still supported for tests or apps
 * that drive the stream outside the bridge.
 */
class SyncFieldPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val previewView = PreviewView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scaleType = PreviewView.ScaleType.FIT_CENTER
        // TextureView-backed preview handles React Native overlays and
        // runtime orientation changes more predictably than SurfaceView.
        // VideoCapture and ImageAnalysis stay bound as separate CameraX
        // use cases, so this only affects the on-screen preview surface.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    /**
     * Coroutine scope that lives for the duration the view is
     * attached to a window. Recreated on each attach so an in-flight
     * collect from a previous attach can't leak.
     */
    private var observerScope: CoroutineScope? = null

    /** Tracks the stream we're currently bound to so we no-op redundant rebinds. */
    private var boundStream: AndroidCameraStream? = null

    init {
        // The FrameLayout itself must paint opaque black: the underlying
        // TextureView (PreviewView) is transparent until the camera
        // produces its first frame, and during native-stack screen
        // transitions on Android that gap lets the previous screen
        // bleed through behind the camera preview. iOS gets this for
        // free because `AVCaptureVideoPreviewLayer`'s backing CALayer
        // is opaque black by default.
        setBackgroundColor(Color.BLACK)
        addView(previewView)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        boundStream = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        observerScope = scope
        scope.launch {
            SyncFieldCameraSession.activeStream.collect { stream ->
                if (stream != null && stream !== boundStream) {
                    bindInternal(stream)
                } else if (stream == null) {
                    // Stream gone — drop the binding so the next attach
                    // starts fresh.
                    boundStream = null
                }
            }
        }
        previewView.post {
            if (boundStream == null) {
                SyncFieldCameraSession.activeStream.value?.let { bindInternal(it) }
            }
        }
    }

    override fun onDetachedFromWindow() {
        observerScope?.cancel()
        observerScope = null
        boundStream = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        updateScaleTypeForBounds(w, h)
        boundStream?.setTargetRotation(currentTargetRotation())
        if (boundStream != null) return
        val stream = boundStream ?: SyncFieldCameraSession.activeStream.value
        stream?.let {
            previewView.post {
                if (boundStream == null) bindInternal(it)
            }
        }
    }

    /**
     * Manually bind to a specific [AndroidCameraStream]. Most apps don't
     * need this — the auto-binding via [SyncFieldCameraSession] covers
     * the common path. Useful for unit tests or apps that drive the
     * stream outside the SyncField bridge module.
     */
    fun bind(stream: AndroidCameraStream) = bindInternal(stream)

    private fun bindInternal(stream: AndroidCameraStream) {
        if (!isAttachedToWindow) return
        if (width <= 0 || height <= 0) {
            postDelayed({
                if (isAttachedToWindow && boundStream !== stream) bindInternal(stream)
            }, 50L)
            return
        }

        if (stream.livePreview == null) {
            // configureUseCases() hasn't run yet — retry once the
            // stream is fully connected.
            postDelayed({
                if (isAttachedToWindow && boundStream !== stream) bindInternal(stream)
            }, 50L)
            return
        }
        updateScaleTypeForBounds(width, height)
        stream.setTargetRotation(currentTargetRotation())
        // Hand the surface to the stream; the stream performs the
        // initial bindToLifecycle internally so the CameraX session
        // boots with the surface attached (instead of binding
        // surface-less and missing the first frames — the bug that
        // showed up on Galaxy S-class devices as "preview is black
        // until the first rotation").
        stream.attachPreviewSurfaceProvider(previewView.surfaceProvider)
        boundStream = stream
        Log.i(
            TAG,
            "Bound camera preview ${width}x$height scaleType=${previewView.scaleType} rotation=${currentTargetRotation()}",
        )
    }

    private fun currentTargetRotation(): Int {
        val displayRotation = display?.rotation ?: previewView.display?.rotation
        if (displayRotation != null && displayRotation != Surface.ROTATION_0) {
            return displayRotation
        }
        return if (width > height) Surface.ROTATION_90 else Surface.ROTATION_0
    }

    /** Set the [PreviewView] scale type. Default is [PreviewView.ScaleType.FIT_CENTER]. */
    fun setScaleType(type: PreviewView.ScaleType) {
        previewView.scaleType = type
    }

    private fun updateScaleTypeForBounds(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // Preserve the whole camera frame. In landscape, FILL_CENTER crops a
        // 16:9 camera stream on tall phone displays and reads as a digital
        // zoom. FIT_CENTER keeps the ultra-wide field of view visible.
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
    }

    private companion object {
        const val TAG = "SyncFieldPreviewView"
    }
}
