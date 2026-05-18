package io.opengraph.syncfield.streams

import kotlin.math.atan
import kotlin.math.max

/**
 * Approximate horizontal field-of-view (degrees) that a camera will
 * produce at its widest reachable zoom level.
 *
 * Why we scale focal by [minZoomRatio]:
 *   - On a single-physical-lens camera, `minZoomRatio == 1.0` and this
 *     reduces to the standard `2·atan(sensor / (2·focal))` formula.
 *   - On a logical multi-camera that encapsulates an ultra-wide
 *     (Pixel 7+, Galaxy S22+, etc.), `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`
 *     often reports only the main lens's focal length but
 *     `CONTROL_ZOOM_RATIO_RANGE.lower` drops below 1 because zooming
 *     out triggers a physical switch to the ultra-wide. Multiplying
 *     focal by `minZoomRatio` recovers the effective focal length the
 *     user gets to use, which lets a single scoring function compare
 *     both camera archetypes apples-to-apples.
 *
 * Returns `null` on any non-positive input — the caller should treat
 * that as "characteristic unavailable, skip this candidate" rather
 * than as zero degrees.
 */
internal fun effectiveHorizontalFovDegrees(
    minFocalLengthMm: Double,
    sensorLongEdgeMm: Double,
    minZoomRatio: Double?,
): Double? {
    if (minFocalLengthMm <= 0.0) return null
    if (sensorLongEdgeMm <= 0.0) return null
    val zoom = minZoomRatio ?: 1.0
    if (zoom <= 0.0) return null
    val effectiveFocal = minFocalLengthMm * zoom
    val longEdge = max(sensorLongEdgeMm, 0.0)
    return Math.toDegrees(2.0 * atan(longEdge / (2.0 * effectiveFocal)))
}
