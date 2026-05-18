package io.opengraph.syncfield.streams

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure-math tests for the camera selection helper that
 * [AndroidCameraStream] uses to pick the widest available back camera.
 *
 * The selection has to handle two device archetypes:
 *
 *  - Single-physical-lens cameras (e.g., a dedicated ultra-wide cam id
 *    that exposes only one focal length) — `minZoomRatio == 1.0`, so
 *    the effective focal length is just the reported min focal length.
 *  - Logical multi-cameras (e.g., Pixel 7+ / Galaxy S22+ default back
 *    camera) that encapsulate the ultra-wide and switch to it when
 *    `setZoomRatio` drops below 1. For these we need to multiply the
 *    min focal length by `minZoomRatio` so the candidate is compared
 *    against single-lens cameras on equal footing.
 *
 * Without this, the algorithm picks a wide-ish secondary physical lens
 * (~96° HFOV) over the logical multi-camera that can switch into the
 * true ~120° ultra-wide via zoom — which is what we observed on a
 * Galaxy device.
 */
class CameraFovScoringTest {

    @Test
    fun `effective HFOV of single-lens ultra-wide`() {
        // Typical phone ultra-wide: 2mm focal, 7mm sensor long edge → ~121°
        val hfov = effectiveHorizontalFovDegrees(
            minFocalLengthMm = 2.0,
            sensorLongEdgeMm = 7.0,
            minZoomRatio = 1.0,
        )
        assertThat(hfov).isWithin(1.0).of(121.0)
    }

    @Test
    fun `effective HFOV of typical main camera`() {
        // Main back lens: 5mm focal, 7mm sensor → ~70°
        val hfov = effectiveHorizontalFovDegrees(
            minFocalLengthMm = 5.0,
            sensorLongEdgeMm = 7.0,
            minZoomRatio = 1.0,
        )
        assertThat(hfov).isWithin(2.0).of(70.0)
    }

    @Test
    fun `logical multi-camera with sub-1 zoom scales focal by minZoom`() {
        // Logical camera reports main lens characteristics
        // (5mm focal, 7mm sensor) but minZoom=0.5 means it can switch
        // into the ultra-wide. Effective focal = 5 * 0.5 = 2.5mm →
        // ~108°, much wider than the 70° at zoom 1.
        val hfov = effectiveHorizontalFovDegrees(
            minFocalLengthMm = 5.0,
            sensorLongEdgeMm = 7.0,
            minZoomRatio = 0.5,
        )
        assertThat(hfov).isWithin(2.0).of(108.0)
    }

    @Test
    fun `logical multi-camera beats wide-ish single-lens`() {
        // Decision under test: a 3.5mm "wide" single-physical lens
        // (HFOV ~90°) vs a logical multi-camera reporting 5mm focal
        // with minZoom=0.5 (HFOV ~108°). The logical should win
        // because zoom-out gets us into the true ultra-wide.
        val physicalWide = effectiveHorizontalFovDegrees(
            minFocalLengthMm = 3.5,
            sensorLongEdgeMm = 7.0,
            minZoomRatio = 1.0,
        )
        val logicalMulti = effectiveHorizontalFovDegrees(
            minFocalLengthMm = 5.0,
            sensorLongEdgeMm = 7.0,
            minZoomRatio = 0.5,
        )
        assertThat(logicalMulti).isGreaterThan(physicalWide)
    }

    @Test
    fun `zero or negative inputs return null`() {
        assertThat(effectiveHorizontalFovDegrees(0.0, 7.0, 1.0)).isNull()
        assertThat(effectiveHorizontalFovDegrees(5.0, 0.0, 1.0)).isNull()
        assertThat(effectiveHorizontalFovDegrees(5.0, 7.0, 0.0)).isNull()
    }

    @Test
    fun `minZoom defaults to 1 when API is older than 30`() {
        // CONTROL_ZOOM_RATIO_RANGE is API 30+. On older devices we
        // pass null and the helper should treat that as zoom=1.0.
        val hfov = effectiveHorizontalFovDegrees(
            minFocalLengthMm = 2.0,
            sensorLongEdgeMm = 7.0,
            minZoomRatio = null,
        )
        // Equivalent to minZoom=1
        val baseline = effectiveHorizontalFovDegrees(2.0, 7.0, 1.0)
        assertThat(hfov).isEqualTo(baseline)
    }
}
