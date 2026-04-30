package io.opengraph.syncfield

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tunable thresholds for the hand field-of-view quality monitor.
 *
 * Defaults mirror syncfield-swift and are intentionally host-overridable:
 * camera placement, detector confidence and task motion vary by deployment.
 */
@Serializable
data class HandQualityConfig(
    val enabled: Boolean = true,
    @SerialName("proximity_warning_extent_norm")
    val proximityWarningExtentNorm: Double = 0.10,
    @SerialName("oof_debounce_ms")
    val oofDebounceMs: Int = 200,
    @SerialName("recovery_debounce_ms")
    val recoveryDebounceMs: Int = 100,
    @SerialName("min_keypoint_confidence")
    val minKeypointConfidence: Double = 0.3,
    @SerialName("min_confident_keypoints_for_bbox")
    val minConfidentKeypointsForBbox: Int = 5,
    @SerialName("spatial_continuity_fallback")
    val spatialContinuityFallback: Boolean = true,
    @SerialName("chirality_confidence_min")
    val chiralityConfidenceMin: Double = 0.7,
    @SerialName("wrist_memory_ms")
    val wristMemoryMs: Int = 1500,
    @SerialName("startup_grace_ms")
    val startupGraceMs: Int = 1000,
    @SerialName("verdict_good_threshold")
    val verdictGoodThreshold: Double = 0.95,
    @SerialName("verdict_reject_threshold")
    val verdictRejectThreshold: Double = 0.80,
) {
    companion object {
        val Default = HandQualityConfig()
    }
}
