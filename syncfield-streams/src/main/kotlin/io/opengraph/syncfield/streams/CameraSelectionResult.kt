package io.opengraph.syncfield.streams

/**
 * Selected back camera + how to bind it.
 *
 * `logicalCameraId` is what we pass to CameraX as the "camera selector"
 * — the top-level (logical or standalone physical) camera CameraX must
 * open. `physicalCameraId` is set when the actual lens we want is a
 * sub-physical hidden inside that logical camera; we then apply
 * `Camera2Interop.Extender.setPhysicalCameraId(physicalCameraId)` on
 * each use case so CameraX routes the request to the right lens
 * inside the logical group.
 *
 * Why both fields:
 *
 *  - On Pixel-style devices the back logical camera (`id=0`) has a
 *    sub-1 zoom range that auto-switches to the ultra-wide sub-physical
 *    when the user calls `setZoomRatio(0.5)`. There the logical wins
 *    on its own (`physicalCameraId = null`) and `setZoomRatio` covers
 *    the lens switch.
 *  - Some OEMs expose hidden ultra-wide sub-physicals. We enumerate
 *    those too, but only pin one directly when no top-level logical
 *    camera can zoom below 1x. CameraX's logical zoom-out path is more
 *    reliable for Preview + VideoCapture sessions.
 *  - On older OEMs the ultra-wide is a standalone top-level camera
 *    (no logical wrapper). There `physicalCameraId == null` and
 *    `logicalCameraId` is the standalone id — same code path as the
 *    Pixel case but without a zoom-out hop.
 */
internal data class CameraSelectionResult(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val effectiveHorizontalFovDegrees: Double,
    val minZoomRatio: Double,
) {
    val isSubPhysical: Boolean get() = physicalCameraId != null
}

/**
 * Inputs to [pickWidestBackCamera]. Each candidate represents a real
 * lens (either a top-level camera or a sub-physical of a logical one).
 */
internal data class BackCameraCandidate(
    val cameraId: String,
    /** Non-null when this candidate is a sub-physical of a logical camera. */
    val owningLogicalCameraId: String?,
    val effectiveHorizontalFovDegrees: Double,
    val minZoomRatio: Double,
) {
    val isSubPhysical: Boolean get() = owningLogicalCameraId != null
}

/**
 * Decide which back lens to bind based on enumerated [candidates].
 *
 * Prefer a CameraX-visible logical/standalone candidate that supports
 * sub-1 zoom. That path keeps CameraX in charge of multi-camera routing
 * and avoids black Preview/VideoCapture sessions caused by direct
 * hidden physical-camera pinning. If no logical zoom-out path exists,
 * fall back to the widest candidate and break ties in favour of
 * logical/standalone cameras.
 */
internal fun pickWidestBackCamera(
    candidates: List<BackCameraCandidate>,
): CameraSelectionResult? {
    if (candidates.isEmpty()) return null
    val logicalZoomOutCandidates = candidates.filter {
        !it.isSubPhysical && it.minZoomRatio < 0.999
    }
    val candidatePool = logicalZoomOutCandidates.ifEmpty { candidates }
    val winner = candidatePool.maxWithOrNull(
        compareBy<BackCameraCandidate> { it.effectiveHorizontalFovDegrees }
            .thenBy { if (it.isSubPhysical) 0 else 1 },
    ) ?: return null
    return CameraSelectionResult(
        logicalCameraId = winner.owningLogicalCameraId ?: winner.cameraId,
        physicalCameraId = if (winner.isSubPhysical) winner.cameraId else null,
        effectiveHorizontalFovDegrees = winner.effectiveHorizontalFovDegrees,
        minZoomRatio = winner.minZoomRatio,
    )
}
