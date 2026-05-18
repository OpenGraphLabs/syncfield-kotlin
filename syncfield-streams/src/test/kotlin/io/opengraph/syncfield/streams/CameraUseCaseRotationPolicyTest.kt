package io.opengraph.syncfield.streams

import android.view.Surface
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CameraUseCaseRotationPolicyTest {

    @Test
    fun `preview rotation is left to PreviewView transform`() {
        val rotations = cameraUseCaseTargetRotations(Surface.ROTATION_90)

        assertThat(rotations.preview).isNull()
    }

    @Test
    fun `capture and analysis keep requested target rotation`() {
        val rotations = cameraUseCaseTargetRotations(Surface.ROTATION_270)

        assertThat(rotations.imageAnalysis).isEqualTo(Surface.ROTATION_270)
        assertThat(rotations.videoCapture).isEqualTo(Surface.ROTATION_270)
    }
}
