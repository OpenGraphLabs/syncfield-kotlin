package io.opengraph.syncfield

import com.google.common.truth.Truth.assertThat
import io.opengraph.syncfield.audio.ChirpEmission
import io.opengraph.syncfield.audio.ChirpPlayer
import io.opengraph.syncfield.audio.ChirpSource
import io.opengraph.syncfield.audio.ChirpSpec
import io.opengraph.syncfield.audio.SilentChirpPlayer
import io.opengraph.syncfield.writers.WriterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionOrchestratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `happy path runs idle → connected → recording → stopping → ingesting → connected`() = runTest {
        val orch = SessionOrchestrator(
            hostId = "host-test",
            outputDirectory = tmp.root,
            chirpPlayer = SilentChirpPlayer(),
            startChirpSpec = null,
            stopChirpSpec = null,
            postStartStabilizationMs = 0.0,
            preStopTailMarginMs = 0.0,
        )
        val s = FakeStream("imu")
        orch.add(s)
        assertThat(orch.state).isEqualTo(SessionState.Idle)

        orch.connect()
        assertThat(orch.state).isEqualTo(SessionState.Connected)
        assertThat(s.connected).isTrue()

        val sp = orch.startRecording()
        assertThat(orch.state).isEqualTo(SessionState.Recording)
        assertThat(sp.hostId).isEqualTo("host-test")
        assertThat(s.startedRecording).isTrue()

        val stopReport = orch.stopRecording()
        assertThat(orch.state).isEqualTo(SessionState.Stopping)
        assertThat(stopReport.streamReports.single().streamId).isEqualTo("imu")

        val ingestReport = orch.ingest { /* no-op */ }
        assertThat(orch.state).isEqualTo(SessionState.Connected)
        assertThat(ingestReport.streamResults["imu"]?.isSuccess).isTrue()

        orch.disconnect()
        assertThat(orch.state).isEqualTo(SessionState.Idle)
        assertThat(s.disconnected).isTrue()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `connect with no streams throws NoStreamsRegistered`() = runTest {
        val orch = SessionOrchestrator(
            hostId = "h",
            outputDirectory = tmp.root,
            startChirpSpec = null,
            stopChirpSpec = null,
        )
        runCatching { orch.connect() }
            .onSuccess { error("expected throw") }
            .onFailure { assertThat(it).isSameInstanceAs(SessionError.NoStreamsRegistered) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `duplicate streamId throws`() = runTest {
        val orch = SessionOrchestrator(
            hostId = "h",
            outputDirectory = tmp.root,
            startChirpSpec = null,
            stopChirpSpec = null,
        )
        orch.add(FakeStream("imu"))
        runCatching { orch.add(FakeStream("imu")) }
            .onSuccess { error("expected throw") }
            .onFailure { assertThat(it).isInstanceOf(SessionError.DuplicateStreamId::class.java) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `start failure rolls back any successfully started streams`() = runTest {
        val orch = SessionOrchestrator(
            hostId = "h",
            outputDirectory = tmp.root,
            startChirpSpec = null,
            stopChirpSpec = null,
        )
        val good = FakeStream("good")
        val bad = FakeStream("bad", failOnStart = true)
        orch.add(good)
        orch.add(bad)
        orch.connect()

        val ex = runCatching { orch.startRecording() }.exceptionOrNull()
        assertThat(ex).isInstanceOf(SessionError.StartFailed::class.java)
        // Rollback should have stopped any started streams.
        assertThat(good.stoppedRecording).isTrue()
        assertThat(orch.state).isEqualTo(SessionState.Connected)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `episode directory is created and contains sync_point and manifest after stop`() = runTest {
        val orch = SessionOrchestrator(
            hostId = "h",
            outputDirectory = tmp.root,
            startChirpSpec = null,
            stopChirpSpec = null,
            postStartStabilizationMs = 0.0,
            preStopTailMarginMs = 0.0,
        )
        orch.add(FakeStream("imu"))
        orch.connect()
        orch.startRecording()
        orch.stopRecording()

        val dir: File = orch.episodeDirectory
        assertThat(dir.exists()).isTrue()
        assertThat(File(dir, "sync_point.json").exists()).isTrue()
        assertThat(File(dir, "session.log").exists()).isTrue()
        assertThat(File(dir, "manifest.json").exists()).isTrue()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `start countdown runs after streams start and before audible start chirp`() = runTest {
        val events = mutableListOf<String>()
        val chirps = RecordingChirpPlayer(events)
        val orch = SessionOrchestrator(
            hostId = "h",
            outputDirectory = tmp.root,
            chirpPlayer = chirps,
            startChirpSpec = ChirpSpec.audibleStart,
            stopChirpSpec = null,
            postStartStabilizationMs = 0.0,
            preStopTailMarginMs = 0.0,
        )
        orch.add(FakeStream("cam", onStart = { events += "stream-start" }))
        orch.connect()

        orch.startRecording(
            countdownSeconds = 3,
            countdownIntervalMs = 0L,
            onCountdownTick = { remaining -> events += "countdown-$remaining" },
        )

        assertThat(events).containsExactly(
            "stream-start",
            "countdown-3",
            "countdown-2",
            "countdown-1",
            "chirp-400.0-2500.0",
        ).inOrder()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stop chirp plays before streams stop`() = runTest {
        val events = mutableListOf<String>()
        val orch = SessionOrchestrator(
            hostId = "h",
            outputDirectory = tmp.root,
            chirpPlayer = RecordingChirpPlayer(events),
            startChirpSpec = null,
            stopChirpSpec = ChirpSpec.audibleStop,
            postStartStabilizationMs = 0.0,
            preStopTailMarginMs = 0.0,
        )
        orch.add(FakeStream("cam", onStop = { events += "stream-stop" }))
        orch.connect()
        orch.startRecording()

        orch.stopRecording()

        assertThat(events).containsExactly(
            "chirp-2500.0-400.0",
            "stream-stop",
        ).inOrder()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `finishRecording closes stopped episode and returns to connected`() = runTest {
        val orch = SessionOrchestrator(
            hostId = "h",
            outputDirectory = tmp.root,
            chirpPlayer = SilentChirpPlayer(),
            startChirpSpec = null,
            stopChirpSpec = null,
            postStartStabilizationMs = 0.0,
            preStopTailMarginMs = 0.0,
        )
        val stream = FakeStream("cam")
        orch.add(stream)
        orch.connect()
        orch.startRecording()
        orch.stopRecording()

        orch.finishRecording()

        assertThat(orch.state).isEqualTo(SessionState.Connected)
        orch.startRecording()
        assertThat(orch.state).isEqualTo(SessionState.Recording)
    }
}

private class FakeStream(
    override val streamId: String,
    private val failOnStart: Boolean = false,
    private val onStart: () -> Unit = {},
    private val onStop: () -> Unit = {},
) : SyncFieldStream {

    override val capabilities = StreamCapabilities(
        requiresIngest = false,
        producesFile = false,
        supportsPreciseTimestamps = true,
    )

    var connected = false
    var disconnected = false
    var startedRecording = false
    var stoppedRecording = false

    override suspend fun prepare() {}

    override suspend fun connect(context: StreamConnectContext) {
        connected = true
    }

    override suspend fun startRecording(clock: SessionClock, writerFactory: WriterFactory) {
        if (failOnStart) throw RuntimeException("simulated start failure")
        startedRecording = true
        onStart()
    }

    override suspend fun stopRecording(): StreamStopReport {
        stoppedRecording = true
        onStop()
        return StreamStopReport(streamId, frameCount = 0, kind = "sensor")
    }

    override suspend fun ingest(
        episodeDirectory: File,
        progress: (Double) -> Unit,
    ): StreamIngestReport = StreamIngestReport(streamId, "$streamId.jsonl", 0)

    override suspend fun disconnect() {
        disconnected = true
    }
}

private class RecordingChirpPlayer(
    private val events: MutableList<String>,
) : ChirpPlayer {
    override val isSilent: Boolean = false

    override suspend fun play(spec: ChirpSpec): ChirpEmission {
        events += "chirp-${spec.fromHz}-${spec.toHz}"
        return ChirpEmission(
            softwareNs = System.nanoTime(),
            hardwareNs = null,
            source = ChirpSource.SoftwareFallback,
        )
    }
}
