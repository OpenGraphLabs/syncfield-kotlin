package io.opengraph.syncfield.streams

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CameraSelectionResultTest {

    @Test
    fun `returns null for empty candidate list`() {
        assertThat(pickWidestBackCamera(emptyList())).isNull()
    }

    @Test
    fun `picks the candidate with widest effective HFOV`() {
        val candidates = listOf(
            BackCameraCandidate(
                cameraId = "0", owningLogicalCameraId = null,
                effectiveHorizontalFovDegrees = 70.0, minZoomRatio = 1.0,
            ),
            BackCameraCandidate(
                cameraId = "2", owningLogicalCameraId = null,
                effectiveHorizontalFovDegrees = 96.2, minZoomRatio = 1.0,
            ),
        )
        val result = pickWidestBackCamera(candidates)!!
        assertThat(result.logicalCameraId).isEqualTo("2")
        assertThat(result.physicalCameraId).isNull()
        assertThat(result.effectiveHorizontalFovDegrees).isWithin(0.01).of(96.2)
    }

    @Test
    fun `prefers CameraX logical zoom-out over hidden sub-physical pinning`() {
        // On current Galaxy/Pixel devices the top-level logical back
        // camera exposes sub-1 zoom and CameraX can switch to ultra-wide
        // with setZoomRatio(0.5). Pinning a hidden physical id directly
        // is less reliable with Preview + VideoCapture and has produced
        // black preview sessions, so logical zoom-out must win even when
        // a hidden physical reports a slightly wider standalone FOV.
        val candidates = listOf(
            BackCameraCandidate(
                cameraId = "0", owningLogicalCameraId = null,
                effectiveHorizontalFovDegrees = 108.0, minZoomRatio = 0.5,
            ),
            BackCameraCandidate(
                cameraId = "51", owningLogicalCameraId = "0",
                effectiveHorizontalFovDegrees = 120.0, minZoomRatio = 1.0,
            ),
        )
        val result = pickWidestBackCamera(candidates)!!
        assertThat(result.logicalCameraId).isEqualTo("0")
        assertThat(result.physicalCameraId).isNull()
        assertThat(result.isSubPhysical).isFalse()
        assertThat(result.minZoomRatio).isWithin(0.01).of(0.5)
    }

    @Test
    fun `uses hidden sub-physical only when no logical zoom-out path exists`() {
        val candidates = listOf(
            BackCameraCandidate(
                cameraId = "0", owningLogicalCameraId = null,
                effectiveHorizontalFovDegrees = 69.7, minZoomRatio = 1.0,
            ),
            BackCameraCandidate(
                cameraId = "51", owningLogicalCameraId = "0",
                effectiveHorizontalFovDegrees = 120.0, minZoomRatio = 1.0,
            ),
        )
        val result = pickWidestBackCamera(candidates)!!
        assertThat(result.logicalCameraId).isEqualTo("0")
        assertThat(result.physicalCameraId).isEqualTo("51")
    }

    @Test
    fun `prefers logical or standalone when FOV ties with sub-physical`() {
        // If a sub-physical and a top-level camera tie on FOV, prefer
        // the top-level one — it doesn't lose multi-camera features.
        val candidates = listOf(
            BackCameraCandidate(
                cameraId = "0", owningLogicalCameraId = null,
                effectiveHorizontalFovDegrees = 120.0, minZoomRatio = 0.5,
            ),
            BackCameraCandidate(
                cameraId = "51", owningLogicalCameraId = "0",
                effectiveHorizontalFovDegrees = 120.0, minZoomRatio = 1.0,
            ),
        )
        val result = pickWidestBackCamera(candidates)!!
        assertThat(result.logicalCameraId).isEqualTo("0")
        assertThat(result.physicalCameraId).isNull()
    }

    @Test
    fun `pinning a sub-physical surfaces the owning logical as bind target`() {
        val result = pickWidestBackCamera(
            listOf(
                BackCameraCandidate(
                    cameraId = "3",
                    owningLogicalCameraId = "0",
                    effectiveHorizontalFovDegrees = 115.0,
                    minZoomRatio = 1.0,
                ),
            ),
        )!!
        assertThat(result.logicalCameraId).isEqualTo("0")
        assertThat(result.physicalCameraId).isEqualTo("3")
    }
}
