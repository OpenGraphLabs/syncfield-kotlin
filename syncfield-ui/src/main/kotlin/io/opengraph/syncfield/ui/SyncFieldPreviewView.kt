package io.opengraph.syncfield.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import io.opengraph.syncfield.streams.AndroidCameraStream
import io.opengraph.syncfield.streams.SyncFieldCameraSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
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
        addView(previewView)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
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
    }

    override fun onDetachedFromWindow() {
        observerScope?.cancel()
        observerScope = null
        super.onDetachedFromWindow()
    }

    /**
     * Manually bind to a specific [AndroidCameraStream]. Most apps don't
     * need this — the auto-binding via [SyncFieldCameraSession] covers
     * the common path. Useful for unit tests or apps that drive the
     * stream outside the SyncField bridge module.
     */
    fun bind(stream: AndroidCameraStream) = bindInternal(stream)

    private fun bindInternal(stream: AndroidCameraStream) {
        val live = stream.livePreview
            ?: throw IllegalStateException(
                "AndroidCameraStream.livePreview is null — call connect() before binding"
            )
        live.setSurfaceProvider(previewView.surfaceProvider)
        boundStream = stream
    }

    /** Set the [PreviewView] scale type. Default is [PreviewView.ScaleType.FILL_CENTER]. */
    fun setScaleType(type: PreviewView.ScaleType) {
        previewView.scaleType = type
    }
}
