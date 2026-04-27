package io.opengraph.syncfield.streams

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoSettingsTest {

    @Test
    fun `HD720 preset is 1280x720 at 30 fps H264`() {
        assertThat(VideoSettings.HD720.width).isEqualTo(1280)
        assertThat(VideoSettings.HD720.height).isEqualTo(720)
        assertThat(VideoSettings.HD720.fps).isEqualTo(30)
        assertThat(VideoSettings.HD720.codec).isEqualTo(VideoCodec.H264)
    }

    @Test
    fun `HD720_60 preset matches egonaut iOS default`() {
        assertThat(VideoSettings.HD720_60.width).isEqualTo(1280)
        assertThat(VideoSettings.HD720_60.height).isEqualTo(720)
        assertThat(VideoSettings.HD720_60.fps).isEqualTo(60)
    }

    @Test
    fun `FullHD preset is 1920x1080`() {
        assertThat(VideoSettings.FullHD.width).isEqualTo(1920)
        assertThat(VideoSettings.FullHD.height).isEqualTo(1080)
    }

    @Test
    fun `Uhd4K preset is 3840x2160`() {
        assertThat(VideoSettings.Uhd4K.width).isEqualTo(3840)
        assertThat(VideoSettings.Uhd4K.height).isEqualTo(2160)
    }

    @Test
    fun `H264 mime type matches platform constant`() {
        assertThat(VideoCodec.H264.mimeType).isEqualTo("video/avc")
    }

    @Test
    fun `HEVC mime type matches platform constant`() {
        assertThat(VideoCodec.HEVC.mimeType).isEqualTo("video/hevc")
    }
}
