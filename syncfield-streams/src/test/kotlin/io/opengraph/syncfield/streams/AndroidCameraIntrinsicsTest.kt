package io.opengraph.syncfield.streams

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-function tests for the camera intrinsics computation that
 * powers [AndroidCameraStream]'s `setIntrinsicMatrixHandler` callback.
 *
 * Two paths must work, mirroring iOS:
 *  - `LENS_INTRINSIC_CALIBRATION` (API 23+, often null on Samsung/Xiaomi)
 *    — exact values, scaled from the camera's active array down to the
 *      recorded output frame.
 *  - Focal-length fallback (`LENS_INFO_AVAILABLE_FOCAL_LENGTHS` +
 *    `SENSOR_INFO_PHYSICAL_SIZE`) — pinhole estimate. iOS's
 *    `CameraIntrinsicsWriter.writeEstimated()` uses the same shape.
 */
class AndroidCameraIntrinsicsTest {

    @Test
    fun `lens-calibration path scales fx_fy_cx_cy from active array to output frame`() {
        // Active array 4000x3000, calibration in those pixels:
        //   fx=3000, fy=3000, cx=2000, cy=1500
        // Output is 1280x720 — fx should scale by 1280/4000 = 0.32 → 960
        // fy scales by 720/3000 = 0.24 → 720, cx by .32 → 640, cy by .24 → 360
        val result = computeIntrinsicsFromLensCalibration(
            calibration = floatArrayOf(3000f, 3000f, 2000f, 1500f, 0f),
            activeArrayWidth = 4000,
            activeArrayHeight = 3000,
            outputWidth = 1280,
            outputHeight = 720,
        )
        assertThat(result).isNotNull()
        result!!
        assertThat(result.fx).isWithin(0.01).of(960.0)
        assertThat(result.fy).isWithin(0.01).of(720.0)
        assertThat(result.cx).isWithin(0.01).of(640.0)
        assertThat(result.cy).isWithin(0.01).of(360.0)
        assertThat(result.sampleWidth).isEqualTo(1280)
        assertThat(result.sampleHeight).isEqualTo(720)
        assertThat(result.source).isEqualTo(DeliveredCameraIntrinsics.Source.LENS_INTRINSIC_CALIBRATION)
    }

    @Test
    fun `lens-calibration path rejects degenerate inputs`() {
        // Zero active array → cannot scale
        assertThat(
            computeIntrinsicsFromLensCalibration(
                calibration = floatArrayOf(3000f, 3000f, 2000f, 1500f, 0f),
                activeArrayWidth = 0,
                activeArrayHeight = 3000,
                outputWidth = 1280,
                outputHeight = 720,
            )
        ).isNull()
        // Too-short calibration array
        assertThat(
            computeIntrinsicsFromLensCalibration(
                calibration = floatArrayOf(3000f, 3000f, 2000f),
                activeArrayWidth = 4000,
                activeArrayHeight = 3000,
                outputWidth = 1280,
                outputHeight = 720,
            )
        ).isNull()
        // Non-positive focal length
        assertThat(
            computeIntrinsicsFromLensCalibration(
                calibration = floatArrayOf(0f, 3000f, 2000f, 1500f, 0f),
                activeArrayWidth = 4000,
                activeArrayHeight = 3000,
                outputWidth = 1280,
                outputHeight = 720,
            )
        ).isNull()
    }

    @Test
    fun `focal-length fallback uses fx = focal × outputW per sensorW formula`() {
        // Typical wide back-camera params:
        //   focal=2.2mm, sensor=6.4 × 4.8 mm, output 1280x720
        // fx = 2.2 × 1280 / 6.4 = 440 px
        // fy = 2.2 × 720  / 4.8 = 330 px
        // cx = 640, cy = 360
        val result = computeIntrinsicsFromFocalLength(
            minFocalLengthMm = 2.2f,
            sensorWidthMm = 6.4f,
            sensorHeightMm = 4.8f,
            outputWidth = 1280,
            outputHeight = 720,
        )
        assertThat(result).isNotNull()
        result!!
        assertThat(result.fx).isWithin(0.01).of(440.0)
        assertThat(result.fy).isWithin(0.01).of(330.0)
        assertThat(result.cx).isWithin(0.01).of(640.0)
        assertThat(result.cy).isWithin(0.01).of(360.0)
        assertThat(result.sampleWidth).isEqualTo(1280)
        assertThat(result.sampleHeight).isEqualTo(720)
        assertThat(result.source).isEqualTo(DeliveredCameraIntrinsics.Source.FOCAL_LENGTH_FALLBACK)
    }

    @Test
    fun `focal-length fallback rejects degenerate inputs`() {
        // Zero focal length
        assertThat(
            computeIntrinsicsFromFocalLength(
                minFocalLengthMm = 0f,
                sensorWidthMm = 6.4f,
                sensorHeightMm = 4.8f,
                outputWidth = 1280,
                outputHeight = 720,
            )
        ).isNull()
        // Zero sensor size
        assertThat(
            computeIntrinsicsFromFocalLength(
                minFocalLengthMm = 2.2f,
                sensorWidthMm = 0f,
                sensorHeightMm = 4.8f,
                outputWidth = 1280,
                outputHeight = 720,
            )
        ).isNull()
        // Zero output
        assertThat(
            computeIntrinsicsFromFocalLength(
                minFocalLengthMm = 2.2f,
                sensorWidthMm = 6.4f,
                sensorHeightMm = 4.8f,
                outputWidth = 0,
                outputHeight = 720,
            )
        ).isNull()
    }

    @Test
    fun `DeliveredCameraIntrinsics carries the source tag`() {
        // Iso parity: app-side writer records `source` in the JSON
        // payload. The data class must preserve it through delivery.
        val v = DeliveredCameraIntrinsics(
            fx = 480.0, fy = 480.0, cx = 640.0, cy = 360.0,
            sampleWidth = 1280, sampleHeight = 720, frameIndex = 0,
            source = DeliveredCameraIntrinsics.Source.LENS_INTRINSIC_CALIBRATION,
        )
        assertThat(v.source).isEqualTo(DeliveredCameraIntrinsics.Source.LENS_INTRINSIC_CALIBRATION)
        assertThat(v.fx).isEqualTo(480.0)
    }
}
