package io.opengraph.syncfield

import com.google.common.truth.Truth.assertThat
import io.opengraph.syncfield.audio.SilentChirpPlayer
import io.opengraph.syncfield.writers.WriterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionOrchestratorQualityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun centered(side: HandSide) = HandObservation(
        chirality = side,
        chiralityConfidence = 0.9,
        confidentKeypoints = List(10) { NormalizedPoint(0.5, 0.5) },
        wrist = NormalizedPoint(0.5, 0.5),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `recording writes hand-quality artifacts`() = runTest {
        val config = HandQualityConfig.Default.copy(
            startupGraceMs = 0,
            oofDebounceMs = 100,
            recoveryDebounceMs = 50,
        )
        val session = SessionOrchestrator(
            hostId = "test-host",
            outputDirectory = tmp.root,
            chirpPlayer = SilentChirpPlayer(),
            startChirpSpec = null,
            stopChirpSpec = null,
            postStartStabilizationMs = 0.0,
            preStopTailMarginMs = 0.0,
            handQualityConfig = config,
        )
        session.add(QualityFakeStream("cam_ego"))
        session.connect()
        session.startRecording()

        repeat(3) { i ->
            session.ingestHandObservations(
                listOf(centered(HandSide.Left), centered(HandSide.Right)),
                frame = i,
                monotonicNs = System.nanoTime(),
            )
            Thread.sleep(50)
        }
        for (i in 3 until 11) {
            session.ingestHandObservations(
                listOf(centered(HandSide.Right)),
                frame = i,
                monotonicNs = System.nanoTime(),
            )
            Thread.sleep(50)
        }
        for (i in 11 until 14) {
            session.ingestHandObservations(
                listOf(centered(HandSide.Left), centered(HandSide.Right)),
                frame = i,
                monotonicNs = System.nanoTime(),
            )
            Thread.sleep(50)
        }
        session.logEvent(
            kind = "audio_cue_route_set",
            monotonicNs = System.nanoTime(),
            endMonotonicNs = null,
            payload = mapOf("route" to "Speaker"),
        )

        session.stopRecording()

        val episode = session.episodeDirectory
        val events = File(episode, "events.jsonl")
        val quality = File(episode, "hand_quality.json")
        assertThat(events.exists()).isTrue()
        assertThat(quality.exists()).isTrue()
        assertThat(events.readText()).contains("hand_out_of_frame")
        assertThat(events.readText()).contains("audio_cue_route_set")
        assertThat(quality.readText()).contains("left_in_frame_pct")
    }
}

private class QualityFakeStream(
    override val streamId: String,
) : SyncFieldStream {
    override val capabilities = StreamCapabilities(
        requiresIngest = false,
        producesFile = true,
        supportsPreciseTimestamps = true,
    )

    override suspend fun prepare() = Unit
    override suspend fun connect(context: StreamConnectContext) = Unit
    override suspend fun startRecording(clock: SessionClock, writerFactory: WriterFactory) = Unit
    override suspend fun stopRecording(): StreamStopReport =
        StreamStopReport(streamId, frameCount = 0, kind = "video")
    override suspend fun ingest(
        episodeDirectory: File,
        progress: (Double) -> Unit,
    ): StreamIngestReport = StreamIngestReport(streamId, "$streamId.mp4", 0)
    override suspend fun disconnect() = Unit
}
