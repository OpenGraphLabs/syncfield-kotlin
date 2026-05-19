package io.opengraph.syncfield.streams

/**
 * Per-stream camera intrinsics delivered by [AndroidCameraStream] once
 * the back camera has been selected and opened. Mirrors the iOS
 * `DeliveredCameraIntrinsics` struct so the app-side writer
 * (`CameraIntrinsicsWriter`) can render the same `camera_intrinsics.json`
 * schema regardless of platform.
 *
 * All values are in **pixels of the output (recorded) frame**, not the
 * sensor's active array — consumers should be able to project a 3D point
 * to the recorded image without any further scaling.
 *
 * On iOS the SDK delivers a fresh intrinsics record per sample buffer
 * (intrinsics change with active-format or zoom switches). On Android the
 * lens config is essentially fixed for the duration of a recording, so
 * the SDK fires the handler once after camera selection completes with
 * `frameIndex = 0`. If a future Android update wires zoom changes into
 * the pipeline, the same callback can fire again with a later frame index.
 */
data class DeliveredCameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val sampleWidth: Int,
    val sampleHeight: Int,
    val frameIndex: Int,
    val source: Source,
) {
    /**
     * How the values were obtained. iOS exposes this implicitly through
     * the writer's `source` JSON field; on Android we tag it on the data
     * class so app-side code can pass it through verbatim.
     */
    enum class Source {
        /** Read directly from `CameraCharacteristics.LENS_INTRINSIC_CALIBRATION`
         *  (API 23+) and scaled to the output frame. */
        LENS_INTRINSIC_CALIBRATION,

        /** Computed from `LENS_INFO_AVAILABLE_FOCAL_LENGTHS.min()` and
         *  `SENSOR_INFO_PHYSICAL_SIZE` via the pinhole approximation.
         *  Always available when those fields are populated; matches the
         *  same FOV-based estimate the iOS app writes pre-attachment. */
        FOCAL_LENGTH_FALLBACK,
    }
}

/**
 * Scale the Camera2 `LENS_INTRINSIC_CALIBRATION` matrix (in active-array
 * pixels) into the output frame's coordinate system.
 *
 * `calibration` is the raw 5-tuple `[fx, fy, cx, cy, skew]` Android
 * documents — see
 * https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_INTRINSIC_CALIBRATION
 *
 * Returns `null` if the inputs are degenerate (zero/negative sizes,
 * non-positive focal length, calibration too short).
 */
internal fun computeIntrinsicsFromLensCalibration(
    calibration: FloatArray,
    activeArrayWidth: Int,
    activeArrayHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
): DeliveredCameraIntrinsics? {
    if (calibration.size < 4) return null
    if (activeArrayWidth <= 0 || activeArrayHeight <= 0) return null
    if (outputWidth <= 0 || outputHeight <= 0) return null

    val fxRaw = calibration[0].toDouble()
    val fyRaw = calibration[1].toDouble()
    val cxRaw = calibration[2].toDouble()
    val cyRaw = calibration[3].toDouble()
    if (!(fxRaw > 0.0) || !(fyRaw > 0.0)) return null

    val sx = outputWidth.toDouble() / activeArrayWidth.toDouble()
    val sy = outputHeight.toDouble() / activeArrayHeight.toDouble()

    return DeliveredCameraIntrinsics(
        fx = fxRaw * sx,
        fy = fyRaw * sy,
        cx = cxRaw * sx,
        cy = cyRaw * sy,
        sampleWidth = outputWidth,
        sampleHeight = outputHeight,
        frameIndex = 0,
        source = DeliveredCameraIntrinsics.Source.LENS_INTRINSIC_CALIBRATION,
    )
}

/**
 * Pinhole estimate from the lens's minimum focal length and the sensor's
 * physical size. Matches the formula iOS's `CameraIntrinsicsWriter.writeEstimated`
 * uses, except that here we have direct sensor metric data so we don't
 * need to round-trip through the FOV.
 *
 *   fx (px) = focal_length_mm * outputWidth_px  / sensorWidth_mm
 *   fy (px) = focal_length_mm * outputHeight_px / sensorHeight_mm
 *   cx (px) = outputWidth  / 2
 *   cy (px) = outputHeight / 2
 *
 * Returns `null` if any input is non-positive.
 */
internal fun computeIntrinsicsFromFocalLength(
    minFocalLengthMm: Float,
    sensorWidthMm: Float,
    sensorHeightMm: Float,
    outputWidth: Int,
    outputHeight: Int,
): DeliveredCameraIntrinsics? {
    if (minFocalLengthMm <= 0f) return null
    if (sensorWidthMm <= 0f || sensorHeightMm <= 0f) return null
    if (outputWidth <= 0 || outputHeight <= 0) return null

    val fx = minFocalLengthMm.toDouble() * outputWidth.toDouble() / sensorWidthMm.toDouble()
    val fy = minFocalLengthMm.toDouble() * outputHeight.toDouble() / sensorHeightMm.toDouble()
    return DeliveredCameraIntrinsics(
        fx = fx,
        fy = fy,
        cx = outputWidth.toDouble() / 2.0,
        cy = outputHeight.toDouble() / 2.0,
        sampleWidth = outputWidth,
        sampleHeight = outputHeight,
        frameIndex = 0,
        source = DeliveredCameraIntrinsics.Source.FOCAL_LENGTH_FALLBACK,
    )
}
