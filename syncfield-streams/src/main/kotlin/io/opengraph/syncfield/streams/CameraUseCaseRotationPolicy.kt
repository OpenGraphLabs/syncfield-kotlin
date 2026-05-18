package io.opengraph.syncfield.streams

internal data class CameraUseCaseTargetRotations(
    val preview: Int?,
    val imageAnalysis: Int,
    val videoCapture: Int,
)

internal fun cameraUseCaseTargetRotations(targetRotation: Int): CameraUseCaseTargetRotations =
    CameraUseCaseTargetRotations(
        // PreviewView owns the display transform. Applying the same target
        // rotation to the Preview use case turns a landscape surface into a
        // portrait buffer on some devices, which shows up as a 90-degree
        // rotated preview centered inside black sidebars.
        preview = null,
        imageAnalysis = targetRotation,
        videoCapture = targetRotation,
    )
