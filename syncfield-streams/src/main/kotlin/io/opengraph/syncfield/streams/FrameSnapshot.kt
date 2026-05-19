package io.opengraph.syncfield.streams

import android.graphics.Bitmap

/**
 * Stable, off-thread-safe view of an `ImageAnalysis` frame.
 *
 * Produced by [AndroidCameraStream] on the camera analyzer thread,
 * delivered to host-installed frame processors on a separate serial
 * thread (see [FrameProcessorGate]). The bitmap is a copy of the
 * underlying `ImageProxy`'s pixel data — the proxy itself is released
 * back to the CameraX buffer pool as soon as the snapshot is built, so
 * the analyzer thread is free to receive the next frame.
 *
 * ## Ownership
 *
 * The SDK owns [bitmap]. Hosts MUST NOT call `bitmap.recycle()` — the
 * SDK recycles it as soon as the processor closure returns (either
 * normally or via thrown exception). Holding a reference to [bitmap]
 * past the closure's return is unsafe.
 *
 * Treat [bitmap] as immutable. The pixel data is shared with no other
 * thread, and the SDK won't mutate it for the lifetime of the
 * processor call.
 */
data class FrameSnapshot(
    /**
     * RGBA frame pixels, dimensions matching the `ImageAnalysis` use
     * case (typically 720p for egocentric capture). `ARGB_8888` config.
     */
    val bitmap: Bitmap,

    /** Monotonic frame index since recording start. Stable across drops. */
    val frameIndex: Int,

    /**
     * Capture timestamp in nanoseconds, sourced from
     * `ImageInfo.timestamp`. Strictly monotonic across frames produced
     * by the same camera session; safe to feed directly into detectors
     * that demand monotonic timing (MediaPipe `.video` mode).
     */
    val timestampNs: Long,

    /**
     * Sensor-to-display rotation hint from `ImageInfo.rotationDegrees`
     * (0, 90, 180, or 270). Hosts pass this to MediaPipe / ML Kit so
     * detection runs on an upright image without an extra rotation
     * copy.
     */
    val rotationDegrees: Int,
)
