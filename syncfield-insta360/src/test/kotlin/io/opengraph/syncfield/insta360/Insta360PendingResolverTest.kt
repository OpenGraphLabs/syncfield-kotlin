package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Insta360PendingResolverTest {
    @Test
    fun matchSegments_returnsSingleCandidateInsideWindow() {
        val window = Insta360PendingResolver.Window(
            startWallMs = 1_768_478_400_000L,
            endWallMs = 1_768_478_410_000L,
            expectedSegments = 1,
        )

        assertThat(
            Insta360PendingResolver.matchSegments(
                uris = listOf(
                    "/DCIM/Camera01/VID_20260115_115800_00_001.mp4",
                    "/DCIM/Camera01/VID_20260115_120003_00_001.mp4",
                    "/DCIM/Camera01/VID_20260115_121000_00_001.mp4",
                ),
                window = window,
            ),
        ).isEqualTo(listOf("/DCIM/Camera01/VID_20260115_120003_00_001.mp4"))
    }

    @Test
    fun matchSegments_returnsAllSegmentsInTimestampOrder() {
        val window = Insta360PendingResolver.Window(
            startWallMs = 1_768_478_400_000L,
            endWallMs = 1_768_478_460_000L,
            expectedSegments = 2,
        )

        assertThat(
            Insta360PendingResolver.matchSegments(
                uris = listOf(
                    "/DCIM/Camera01/LRV_20260115_120030_00_002.mp4",
                    "/DCIM/Camera01/VID_20260115_120030_00_002.mp4",
                    "/DCIM/Camera01/VID_20260115_120000_00_001.mp4",
                ),
                window = window,
            ),
        ).isEqualTo(listOf(
            "/DCIM/Camera01/VID_20260115_120000_00_001.mp4",
            "/DCIM/Camera01/VID_20260115_120030_00_002.mp4",
        ))
    }

    @Test
    fun parseFilenameTimestamp_acceptsUnderscoreAndDashFormats() {
        assertThat(
            Insta360PendingResolver.parseFilenameTimestampMs(
                "/DCIM/Camera01/VID_20260115_120000_00_001.mp4"),
        ).isEqualTo(1_768_478_400_000L)
        assertThat(
            Insta360PendingResolver.parseFilenameTimestampMs(
                "/DCIM/Camera01/VID-2026-01-15-120000.mp4"),
        ).isEqualTo(1_768_478_400_000L)
    }

    @Test
    fun videoURIFallback_prefersNewestMp4OverLowResolutionAndInsv() {
        assertThat(
            Insta360VideoURIFallback.bestCandidate(
                listOf(
                    "/DCIM/Camera01/VID_20260115_120000_00_001.insv",
                    "/DCIM/Camera01/LRV_20260115_120500_00_003.mp4",
                    "/DCIM/Camera01/VID_20260115_120000_00_002.mp4",
                ),
            ),
        ).isEqualTo("/DCIM/Camera01/VID_20260115_120000_00_002.mp4")
    }

    @Test
    fun matchSegments_returnsEmptyWhenWindowMisses() {
        val window = Insta360PendingResolver.Window(
            startWallMs = 1_768_478_400_000L,
            endWallMs = 1_768_478_410_000L,
        )

        assertThat(
            Insta360PendingResolver.matchSegments(
                uris = listOf("/DCIM/Camera01/VID_20260115_130000_00_001.mp4"),
                window = window,
            ),
        ).isEmpty()
    }
}
