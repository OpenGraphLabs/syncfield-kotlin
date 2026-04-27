package io.opengraph.syncfield.streams

/**
 * Video codec the Android camera stream encodes into. Mirrors the
 * iOS-side `VideoCodec` so cross-platform configuration stays in
 * lock-step.
 */
enum class VideoCodec(val mimeType: String) {
    /** H.264 / AVC — broadly compatible. */
    H264("video/avc"),
    /** HEVC / H.265 — ~40 % smaller at matched quality, hardware-only on most phones. */
    HEVC("video/hevc"),
}

/**
 * Output-file settings for [AndroidCameraStream]. Defaults reproduce
 * the egonaut iPhone defaults (720p H.264 @ 60 fps).
 */
data class VideoSettings(
    val width: Int,
    val height: Int,
    val codec: VideoCodec = VideoCodec.H264,
    /**
     * Optional average bitrate in bits-per-second. `null` lets the encoder
     * pick the default for the codec/size — usually fine. Set explicitly
     * when you need predictable file sizes.
     */
    val bitrate: Int? = null,
    /**
     * Target frame rate. If the device's selected camera doesn't support
     * this fps at (width, height), CameraX falls back to its highest
     * supported rate at that size — capture still succeeds.
     */
    val fps: Int = 30,
) {
    companion object {
        val HD720    = VideoSettings(1280, 720, fps = 30)
        val HD720_60 = VideoSettings(1280, 720, fps = 60)
        val FullHD   = VideoSettings(1920, 1080, fps = 30)
        val Uhd4K    = VideoSettings(3840, 2160, fps = 30)
    }
}
