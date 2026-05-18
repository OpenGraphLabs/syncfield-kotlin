package io.opengraph.syncfield

import io.opengraph.syncfield.audio.ChirpEmission
import io.opengraph.syncfield.audio.ChirpPlayer
import io.opengraph.syncfield.audio.ChirpSpec
import io.opengraph.syncfield.audio.SilentChirpPlayer
import io.opengraph.syncfield.writers.EventWriter
import io.opengraph.syncfield.writers.Manifest
import io.opengraph.syncfield.writers.ManifestWriter
import io.opengraph.syncfield.writers.SessionLogWriter
import io.opengraph.syncfield.writers.SyncFieldJson
import io.opengraph.syncfield.writers.WriterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Top-level result of [SessionOrchestrator.stopRecording].
 */
data class StopReport(val streamReports: List<StreamStopReport>)

/**
 * Streamed during ingest so callers can render a per-stream progress bar.
 */
data class IngestProgress(val streamId: String, val fraction: Double)

/**
 * Final ingest outcome. `streamResults[id]` is `Result.success` when the
 * stream's ingest call returned, otherwise `Result.failure(error)`.
 */
data class IngestReport(val streamResults: Map<String, Result<StreamIngestReport>>)

/**
 * Drives the recording lifecycle for one episode. Mirrors the Swift
 * `SessionOrchestrator` actor — the state machine, manifest layout and
 * sync_point.json contents are wire-compatible across platforms.
 *
 * Concurrency model: every public mutator suspends and goes through the
 * internal [Mutex], which gives us actor-equivalent serialisation
 * without depending on the deprecated coroutine `actor` builder.
 */
class SessionOrchestrator(
    private val hostId: String,
    private val outputDirectory: File,
    chirpPlayer: ChirpPlayer? = null,
    private val startChirpSpec: ChirpSpec? = ChirpSpec.defaultStart,
    private val stopChirpSpec: ChirpSpec? = ChirpSpec.defaultStop,
    private val postStartStabilizationMs: Double = 200.0,
    private val preStopTailMarginMs: Double = 200.0,
    private var handQualityConfig: HandQualityConfig = HandQualityConfig.Default,
) {

    private val mutex = Mutex()
    private val bus = HealthBus()
    private val chirpPlayer: ChirpPlayer = chirpPlayer ?: SilentChirpPlayer()

    private val streams: MutableList<SyncFieldStream> = mutableListOf()
    private var sessionId: String = ""
    private var logWriter: SessionLogWriter? = null
    private var activeClock: SessionClock? = null
    private var eventWriter: EventWriter? = null
    private var handQualityMonitor: HandQualityMonitor? = null
    private var recordingStartMonotonicNs: Long = 0L

    private var startEmission: ChirpEmission? = null
    private var stopEmission: ChirpEmission? = null
    private var currentSyncPoint: SyncPoint? = null

    var state: SessionState = SessionState.Idle
        private set

    var episodeDirectory: File = File("/")
        private set

    val healthEvents: SharedFlow<HealthEvent> = bus.events

    /**
     * Register a stream with the session.
     *
     * Allowed in [SessionState.Idle] (the usual pre-connect setup) and
     * in [SessionState.Connected] (a stream paired externally — e.g. an
     * Insta360 wrist camera that an RN bridge adopts after the user
     * starts pairing). Streams added post-connect do NOT receive
     * [SyncFieldStream.connect]; the caller is responsible for ensuring
     * the stream is already connected.
     */
    suspend fun add(stream: SyncFieldStream) = mutex.withLock {
        if (state != SessionState.Idle && state != SessionState.Connected) {
            throw SessionError.InvalidTransition(state, state)
        }
        if (streams.any { it.streamId == stream.streamId }) {
            throw SessionError.DuplicateStreamId(stream.streamId)
        }
        streams.add(stream)
    }

    suspend fun connect() = mutex.withLock {
        requireState(SessionState.Idle, SessionState.Connected)
        if (streams.isEmpty()) throw SessionError.NoStreamsRegistered

        sessionId = UUID.randomUUID().toString().replace("-", "").take(12).lowercase()
        val connected: MutableList<SyncFieldStream> = mutableListOf()
        for (s in streams) {
            try {
                s.prepare()
                s.connect(StreamConnectContext(sessionId, hostId, bus))
                connected.add(s)
            } catch (t: Throwable) {
                for (already in connected) {
                    runCatching { already.disconnect() }
                }
                state = SessionState.Idle
                throw StreamError(s.streamId, t)
            }
        }
        state = SessionState.Connected
    }

    suspend fun startRecording(
        countdownMs: Long = 0L,
        countdownSeconds: Int = 0,
        countdownIntervalMs: Long = 1_000L,
        onCountdownTick: suspend (Int) -> Unit = {},
    ): SyncPoint {
        val anchor = mutex.withLock {
            requireState(SessionState.Connected, SessionState.Recording)

            episodeDirectory = makeEpisodeDirectory()
            val clock = SessionClock()
            val factory = WriterFactory(episodeDirectory)

            val a = clock.anchor(hostId)
            writeSyncPoint(a)
            val writer = SessionLogWriter(File(episodeDirectory, "session.log"))
            logWriter = writer
            writer.append(kind = "state", detail = "connected->recording")
            activeClock = clock
            recordingStartMonotonicNs = clock.nowMonotonicNs()
            val evWriter = factory.makeEventWriter()
            eventWriter = evWriter
            handQualityMonitor = HandQualityMonitor(
                config = handQualityConfig,
                recordingStartMonotonicNs = recordingStartMonotonicNs,
                eventWriter = evWriter,
            )

            if (countdownMs > 0) delay(countdownMs)

            // Atomic start: launch all concurrently; roll back any that
            // succeeded if any throw. We record `started` from inside
            // each child coroutine so the list is correct even when
            // structured cancellation tears the scope down — `awaitAll`
            // would otherwise throw before we get to populate the list.
            val started: MutableList<String> =
                java.util.Collections.synchronizedList(mutableListOf())
            try {
                coroutineScope {
                    val deferreds = streams.map { s ->
                        async {
                            s.startRecording(clock, factory)
                            started.add(s.streamId)
                            s.streamId
                        }
                    }
                    deferreds.awaitAll()
                }
            } catch (t: Throwable) {
                for (s in streams) if (started.contains(s.streamId)) {
                    runCatching { s.stopRecording() }
                }
                episodeDirectory.deleteRecursively()
                state = SessionState.Connected
                throw SessionError.StartFailed(t, started)
            }

            state = SessionState.Recording
            a
        }

        if (countdownSeconds > 0) {
            for (remaining in countdownSeconds downTo 1) {
                onCountdownTick(remaining)
                if (countdownIntervalMs > 0) delay(countdownIntervalMs)
            }
        }

        // Chirp emission happens outside the mutex so the player's
        // suspend functions don't serialise the whole orchestrator.
        if (startChirpSpec != null) {
            if (postStartStabilizationMs > 0) {
                delay(postStartStabilizationMs.toLong())
            }
            startEmission = chirpPlayer.play(startChirpSpec)
        }

        return mutex.withLock {
            val emission = startEmission
            val updated = if (emission != null) {
                anchor.copy(
                    chirpStartNs = emission.bestNs,
                    chirpStartSource = emission.source,
                    chirpSpec = startChirpSpec,
                ).also { writeSyncPoint(it) }
            } else {
                anchor
            }
            currentSyncPoint = updated
            updated
        }
    }

    suspend fun stopRecording(): StopReport {
        // Stop chirp emission outside the mutex — the player can take
        // tens of ms.
        if (stopChirpSpec != null) {
            stopEmission = chirpPlayer.play(stopChirpSpec)
            val waitMs = stopChirpSpec.durationMs + preStopTailMarginMs
            delay(waitMs.toLong())
        }

        return mutex.withLock {
            requireState(SessionState.Recording, SessionState.Stopping)

            val emission = stopEmission
            if (emission != null) {
                val sp = currentSyncPoint
                if (sp != null) {
                    val updated = sp.copy(
                        chirpStopNs = emission.bestNs,
                        chirpStopSource = emission.source,
                    )
                    writeSyncPoint(updated)
                    currentSyncPoint = updated
                }
            }

            // Run each stream's stopRecording concurrently. Errors are
            // collected rather than short-circuited so that a failing
            // BLE camera doesn't cancel a healthy stream's finalise.
            val reports: MutableList<StreamStopReport> = mutableListOf()
            var firstError: Throwable? = null
            coroutineScope {
                val deferreds = streams.map { s ->
                    async {
                        runCatching { s.stopRecording() }
                            .fold(
                                onSuccess = { it to null },
                                onFailure = { null to StreamError(s.streamId, it) },
                            )
                    }
                }
                deferreds.awaitAll().forEach { (report, error) ->
                    if (report != null) reports.add(report)
                    if (error != null && firstError == null) firstError = error
                }
            }
            logWriter?.append(kind = "state", detail = "recording->stopping")

            val monitor = handQualityMonitor
            if (monitor != null) {
                val stopNs = activeClock?.nowMonotonicNs() ?: recordingStartMonotonicNs
                val stats = monitor.qualityStats(
                    recordingStartMonotonicNs = recordingStartMonotonicNs,
                    stopMonotonicNs = stopNs,
                )
                monitor.finalize(stopMonotonicNs = stopNs, stopFrame = -1)
                val summary = HandQualitySummaryBuilder.build(stats, handQualityConfig)
                runCatching {
                    HandQualitySummaryBuilder.write(summary, File(episodeDirectory, "hand_quality.json"))
                }
            }
            eventWriter = null
            handQualityMonitor = null

            // Write manifest at stop time too — host apps that defer
            // ingest still need a manifest in the episode directory.
            val manifestResults: Map<String, Result<StreamIngestReport>> =
                reports.associate { r ->
                    r.streamId to Result.success(
                        StreamIngestReport(r.streamId, filePath = null, frameCount = r.frameCount)
                    )
                }
            runCatching { writeManifest(manifestResults) }

            state = SessionState.Stopping
            firstError?.let { throw it }
            StopReport(reports)
        }
    }

    suspend fun ingest(progress: (IngestProgress) -> Unit): IngestReport = mutex.withLock {
        requireState(SessionState.Stopping, SessionState.Ingesting)
        state = SessionState.Ingesting

        val results: MutableMap<String, Result<StreamIngestReport>> = mutableMapOf()
        for (s in streams) {
            val id = s.streamId
            try {
                val report = s.ingest(episodeDirectory) { fraction ->
                    progress(IngestProgress(id, fraction))
                }
                results[id] = Result.success(report)
            } catch (t: Throwable) {
                bus.publish(HealthEvent.IngestFailed(id, t))
                results[id] = Result.failure(t)
            }
        }
        writeManifest(results)
        logWriter?.let {
            it.append(kind = "state", detail = "ingesting->connected")
            it.close()
        }
        logWriter = null

        state = SessionState.Connected
        IngestReport(results)
    }

    /**
     * Close a stopped episode without running stream ingest.
     *
     * Host apps that defer collection/upload still need the session to
     * return to Connected after stop; otherwise the next recording attempt
     * starts from Stopping and fails its state transition.
     */
    suspend fun finishRecording() = mutex.withLock {
        if (state == SessionState.Connected) return@withLock
        requireState(SessionState.Stopping, SessionState.Connected)
        logWriter?.let {
            it.append(kind = "state", detail = "stopping->connected")
            it.close()
        }
        logWriter = null
        eventWriter = null
        handQualityMonitor = null
        state = SessionState.Connected
    }

    suspend fun disconnect() = mutex.withLock {
        requireState(SessionState.Connected, SessionState.Idle)
        for (s in streams) {
            runCatching { s.disconnect() }
        }
        state = SessionState.Idle
    }

    // MARK: Private

    private fun requireState(expected: SessionState, next: SessionState) {
        val allowed = listOf(
            SessionState.Idle to SessionState.Connected,
            SessionState.Connected to SessionState.Recording,
            SessionState.Recording to SessionState.Stopping,
            SessionState.Stopping to SessionState.Ingesting,
            SessionState.Stopping to SessionState.Connected,
            SessionState.Ingesting to SessionState.Connected,
            SessionState.Connected to SessionState.Idle,
        )
        if (state != expected || (expected to next) !in allowed) {
            throw SessionError.InvalidTransition(state, next)
        }
    }

    private fun makeEpisodeDirectory(): File {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val short = UUID.randomUUID().toString().replace("-", "").take(6).lowercase()
        val dir = File(outputDirectory, "ep_${stamp}_$short")
        dir.mkdirs()
        return dir
    }

    private fun writeSyncPoint(sp: SyncPoint) {
        val out = File(episodeDirectory, "sync_point.json")
        val tmp = File(episodeDirectory, "sync_point.json.tmp")
        tmp.writeText(SyncFieldJson.encodeToString(SyncPoint.serializer(), sp))
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true); tmp.delete()
        }
    }

    private fun writeManifest(results: Map<String, Result<StreamIngestReport>>) {
        val entries = streams.map { s ->
            val report = results[s.streamId]?.getOrNull()
            Manifest.StreamEntry(
                streamId    = s.streamId,
                filePath    = report?.filePath ?: defaultFilePath(
                    streamId = s.streamId,
                    kind = if (s.capabilities.producesFile) "video" else "sensor",
                ),
                frameCount  = report?.frameCount ?: 0,
                kind        = if (s.capabilities.producesFile) "video" else "sensor",
                capabilities = s.capabilities,
            )
        }
        val manifest = Manifest(
            sdkVersion = SyncFieldVersion.current,
            hostId = hostId,
            role = "single",
            streams = entries,
        )
        ManifestWriter.write(manifest, File(episodeDirectory, "manifest.json"))
    }

    private fun defaultFilePath(streamId: String, kind: String): String =
        if (kind == "video") "$streamId.mp4" else "$streamId.jsonl"

    suspend fun setHandQualityConfig(config: HandQualityConfig) = mutex.withLock {
        handQualityConfig = config
    }

    suspend fun handQualityEvents(): Flow<HandQualityEvent> = mutex.withLock {
        handQualityMonitor?.events ?: kotlinx.coroutines.flow.emptyFlow()
    }

    suspend fun ingestHandObservations(
        observations: List<HandObservation>,
        frame: Int,
        monotonicNs: Long,
    ) {
        val monitor = mutex.withLock { handQualityMonitor }
        monitor?.ingest(observations, frame, monotonicNs)
    }

    suspend fun logEvent(
        kind: String,
        monotonicNs: Long,
        endMonotonicNs: Long?,
        payload: Map<String, Any?>,
    ) {
        val writer = mutex.withLock { eventWriter }
        val ev = writer ?: return
        if (endMonotonicNs != null && endMonotonicNs != monotonicNs) {
            val handle = ev.appendIntervalStart(
                kind = kind,
                startMonotonicNs = monotonicNs,
                startFrame = -1,
                payload = payload,
            )
            ev.closeInterval(handle, endMonotonicNs, endFrame = -1)
        } else {
            ev.appendPoint(kind, monotonicNs, payload)
        }
        ev.flush()
    }
}
