package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Insta360CollectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun groupByCamera_orderIsDeterministic() {
        val ep1 = File("/episode1")
        val ep2 = File("/episode2")
        val items = listOf(
            ep("c_camera", "s_z", ep1),
            ep("a_camera", "s_a", ep2),
            ep("a_camera", "s_b", ep1),
            ep("b_camera", "s_x", ep1),
        )
        val grouped = Insta360Collector.groupByCamera(items)
        assertThat(grouped.map { it.first }).containsExactly("a_camera", "b_camera", "c_camera").inOrder()
        // Within a UUID, sorted by (episodeDir.path, streamId)
        val aGroup = grouped.first { it.first == "a_camera" }.second
        assertThat(aGroup.map { it.episodeDir.path }).containsExactly("/episode1", "/episode2").inOrder()
    }

    @Test
    fun groupByCamera_empty_returnsEmpty() {
        assertThat(Insta360Collector.groupByCamera(emptyList())).isEmpty()
    }

    @Test
    fun groupByCamera_singleCameraSingleEpisode() {
        val ep1 = File("/episode1")
        val grouped = Insta360Collector.groupByCamera(
            listOf(
                ep("uuid-A", "s2", ep1),
                ep("uuid-A", "s1", ep1),
            )
        )
        assertThat(grouped).hasSize(1)
        assertThat(grouped[0].first).isEqualTo("uuid-A")
        assertThat(grouped[0].second.map { it.sidecar.streamId }).containsExactly("s1", "s2").inOrder()
    }

    @Test
    fun prefetchPairCameras_continuesOnIndividualFailures() = runTest {
        val groups = listOf(
            "A" to listOf(ep("A", "s1", File("/ep"))),
            "B" to listOf(ep("B", "s2", File("/ep"))),
            "C" to listOf(ep("C", "s3", File("/ep"))),
        )
        val pairedOk = mutableListOf<String>()
        val outcome = Insta360Collector.prefetchPairCameras(groups) { uuid, _ ->
            if (uuid == "B") throw RuntimeException("B unreachable")
            pairedOk += uuid
        }
        assertThat(outcome.prefetchedUUIDs).containsExactly("A", "C").inOrder()
        assertThat(outcome.failedUUIDs).containsExactly("B")
        assertThat(outcome.wasCancelled).isFalse()
    }

    @Test
    fun prefetchPairCameras_respectsCancellation() = runTest {
        val groups = listOf(
            "A" to listOf(ep("A", "s1", File("/ep"))),
            "B" to listOf(ep("B", "s2", File("/ep"))),
        )
        val outcome = Insta360Collector.prefetchPairCameras(groups) { uuid, _ ->
            if (uuid == "B") throw kotlinx.coroutines.CancellationException("user")
        }
        assertThat(outcome.wasCancelled).isTrue()
        assertThat(outcome.prefetchedUUIDs).containsExactly("A")
    }

    @Test
    fun prefetchPairCameras_passesPreferredNameHint() = runTest {
        val groups = listOf(
            "A" to listOf(ep("A", "s1", File("/ep"), bleName = "GO XYZ123")),
        )
        var seenName: String? = null
        Insta360Collector.prefetchPairCameras(groups) { _, preferredName ->
            seenName = preferredName
        }
        assertThat(seenName).isEqualTo("GO XYZ123")
    }

    // --- helper -------------------------------------------------------------

    private fun ep(
        uuid: String,
        streamId: String,
        episodeDir: File,
        bleName: String = "GO ${uuid.takeLast(6)}",
    ) = Insta360PendingSidecar.EpisodePending(
        episodeDir = episodeDir,
        sidecar = Insta360PendingSidecar(
            streamId = streamId,
            bleUuid = uuid,
            bleName = bleName,
            cameraFileURI = "/DCIM/$streamId.mp4",
            bleAckMonotonicNs = 1L,
            role = "left",
            savedAt = "2026-05-17T05:30:00Z",
        ),
    )
}
