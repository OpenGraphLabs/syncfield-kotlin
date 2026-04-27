package io.opengraph.syncfield.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import io.opengraph.syncfield.streams.AndroidCameraStream

/**
 * Drop-in preview view for [AndroidCameraStream] — Android counterpart
 * of the iOS `SyncFieldPreviewView`.
 *
 * Wraps CameraX's [PreviewView] in a `FrameLayout` so consumers can
 * lay it out with constraint or relative layouts without exposing the
 * full PreviewView API surface. Call [bind] right after the stream's
 * `connect()` resolves; the view detaches automatically when the
 * stream is disconnected (no explicit teardown needed).
 *
 * Mirrors the Swift API:
 * ```
 * let preview = SyncFieldPreviewView(stream: stream)  // iOS
 * ```
 * vs.
 * ```kotlin
 * val preview = SyncFieldPreviewView(context).also { it.bind(stream) }  // Android
 * ```
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

    init {
        addView(previewView)
    }

    /**
     * Wire the underlying [PreviewView] to a connected
     * [AndroidCameraStream]. Idempotent — safe to call again after the
     * stream is reconnected.
     */
    fun bind(stream: AndroidCameraStream) {
        val live = stream.livePreview
            ?: throw IllegalStateException(
                "AndroidCameraStream.livePreview is null — call connect() before bind()")
        live.setSurfaceProvider(previewView.surfaceProvider)
    }

    /** Set the [PreviewView] scale type. Default is [PreviewView.ScaleType.FILL_CENTER]. */
    fun setScaleType(type: PreviewView.ScaleType) {
        previewView.scaleType = type
    }
}
