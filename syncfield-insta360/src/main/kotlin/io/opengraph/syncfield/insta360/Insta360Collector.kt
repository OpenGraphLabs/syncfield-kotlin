package io.opengraph.syncfield.insta360

import android.content.Context
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Multi-camera batch downloader.
 *
 * Mirrors `Insta360Collector.swift` with three load-bearing invariants:
 *
 * 1. **Deterministic order** — episodes are grouped by camera UUID (alphabetical),
 *    then per-camera items sorted by `(episodeDir, streamId)`. Downstream Python
 *    pipeline tooling expects this ordering.
 * 2. **Prefetch pairing** — before the per-camera sequential loop, every target
 *    camera is paired up-front. Each `BLEController.pair()` starts the 2 s
 *    heartbeat — so cameras 2..N stay alive while camera 1 is on WiFi. Without
 *    prefetch, the second camera could time-out into deep sleep during the
 *    first camera's mp4 download.
 * 3. **Radio gate wrap** — each camera's `downloadBatch` is wrapped in
 *    `Insta360ConnectionCoordinator.withWiFi(uuid)`. The gate pauses the lease
 *    holder's heartbeat (AP-bound can't service BLE) and demotes other cameras
 *    to slow mode (8 s by default) — minimizes radio collisions during download.
 *
 * Per-item failures are isolated — one camera's transient drop doesn't poison
 * the rest of the batch. `CancellationException` propagates normally.
 */
object Insta360Collector {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private val cancelMutex = Mutex()
    @Volatile private var activeJob: Job? = null

    /** Per-file collect progress event. Bridge forwards as `syncfield:collect`. */
    data class Progress(
        val episodeDir: File,
        val streamId: String,
        val bleUuid: String,
        val cameraLabel: String?,
        val phase: String,       // "pairing" | "awaiting_wifi_join" | "downloading" | "done" | "failed" | "skipped"
        val fraction: Double,    // 0.0 .. 1.0
        val ssid: String? = null,
        val error: String? = null,
    )

    /** Result of a single pending download attempt. */
    data class Result(
        val episodeDir: File,
        val streamId: String,
        val bleUuid: String,
        val filePath: File?,
        val error: Throwable?,
    ) {
        val success: Boolean get() = error == null && filePath != null
    }

    /** Outcome of the pre-flight prefetch pairing pass. */
    data class PrefetchOutcome(
        val prefetchedUUIDs: List<String>,
        val failedUUIDs: List<String>,
        val wasCancelled: Boolean,
    )

    /** Collect every pending sidecar in [episodeDir]. */
    suspend fun collectEpisode(
        context: Context,
        episodeDir: File,
        progress: suspend (Progress) -> Unit = {},
    ): List<Result> {
        val pendings = Insta360PendingSidecar.scan(episodeDir)
            .map { Insta360PendingSidecar.EpisodePending(episodeDir, it) }
        return collectInternal(context, pendings, progress)
    }

    /** Collect every pending sidecar across the supplied episode directories. */
    suspend fun collectEpisodes(
        context: Context,
        episodeDirs: List<File>,
        progress: suspend (Progress) -> Unit = {},
    ): List<Result> {
        val pendings = episodeDirs.flatMap { dir ->
            Insta360PendingSidecar.scan(dir).map { Insta360PendingSidecar.EpisodePending(dir, it) }
        }
        return collectInternal(context, pendings, progress)
    }

    /** Recursively collect every pending sidecar under [root]. */
    suspend fun collectAll(
        context: Context,
        root: File,
        progress: suspend (Progress) -> Unit = {},
    ): List<Result> {
        val pendings = Insta360PendingSidecar.scanRecursive(root)
        return collectInternal(context, pendings, progress)
    }

    /** List downloadable files for one paired camera over its Wi-Fi AP. */
    suspend fun listFiles(
        context: Context,
        uuid: String,
        @Suppress("UNUSED_PARAMETER")
        preferredName: String? = null,
    ): List<Insta360FileInfo> {
        val controller = Insta360BluetoothHub.pair(context, uuid)
        val (ssid, passphrase) = controller.wifiCredentials()
        enableCameraWifiForDownload(controller, uuid)
        return Insta360ConnectionCoordinator.withWiFi(uuid) {
            Insta360WiFiDownloader(context).listFiles(ssid, passphrase)
        }
    }

    /** Cancel any in-flight collect job. Safe to call when nothing is running. */
    suspend fun cancel() {
        cancelMutex.withLock {
            activeJob?.cancel(CancellationException("Insta360Collector.cancel()"))
            activeJob = null
        }
    }

    // --- Helpers (exposed for tests) ---------------------------------------

    /**
     * Group sidecars by camera UUID. UUIDs sorted alphabetically; within a UUID,
     * sidecars sorted by `(episodeDir.path, streamId)`. Returned list preserves
     * this order for deterministic test assertions + downstream pipeline parity.
     */
    internal fun groupByCamera(
        items: List<Insta360PendingSidecar.EpisodePending>,
    ): List<Pair<String, List<Insta360PendingSidecar.EpisodePending>>> {
        return items
            .groupBy { it.sidecar.bleUuid }
            .toSortedMap()
            .map { (uuid, list) ->
                uuid to list.sortedWith(
                    compareBy({ it.episodeDir.path }, { it.sidecar.streamId })
                )
            }
    }

    /**
     * Iterate [groups] and try to pair each camera. Individual failures are
     * swallowed (the per-camera download loop will retry pair). Returns the
     * outcome for diagnostics.
     */
    internal suspend fun prefetchPairCameras(
        groups: List<Pair<String, List<Insta360PendingSidecar.EpisodePending>>>,
        pair: suspend (uuid: String, preferredName: String?) -> Unit,
    ): PrefetchOutcome {
        val prefetched = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for ((uuid, list) in groups) {
            try {
                currentCoroutineContext().ensureActive()
                val preferredName = list.firstOrNull()?.sidecar?.bleName
                pair(uuid, preferredName)
                prefetched.add(uuid)
                InstaLog.log(
                    InstaLogCategory.COLLECT, event = "prefetch_pair_ok",
                    fields = mapOf("uuid" to uuid),
                )
            } catch (e: CancellationException) {
                return PrefetchOutcome(prefetched, failed, wasCancelled = true)
            } catch (t: Throwable) {
                failed.add(uuid)
                InstaLog.log(
                    InstaLogCategory.COLLECT, level = InstaLogLevel.WARN,
                    event = "prefetch_pair_failed",
                    fields = mapOf("uuid" to uuid, "error" to (t.message ?: t::class.java.simpleName)),
                )
            }
        }
        return PrefetchOutcome(prefetched, failed, wasCancelled = false)
    }

    // --- Internal collect ---------------------------------------------------

    private suspend fun collectInternal(
        context: Context,
        pendings: List<Insta360PendingSidecar.EpisodePending>,
        progress: suspend (Progress) -> Unit,
    ): List<Result> {
        if (pendings.isEmpty()) return emptyList()
        cancelMutex.withLock {
            activeJob?.cancel(CancellationException("superseded by new collect"))
        }
        return coroutineScope {
            cancelMutex.withLock { activeJob = currentCoroutineContext()[Job] }
            try {
                runCollect(context, pendings, progress)
            } finally {
                cancelMutex.withLock { if (activeJob == currentCoroutineContext()[Job]) activeJob = null }
            }
        }
    }

    private suspend fun runCollect(
        context: Context,
        pendings: List<Insta360PendingSidecar.EpisodePending>,
        progress: suspend (Progress) -> Unit,
    ): List<Result> {
        val groups = groupByCamera(pendings)
        InstaLog.log(
            InstaLogCategory.COLLECT, event = "collect_started",
            fields = mapOf("camera_count" to groups.size, "file_count" to pendings.size),
        )

        // Step 1: prefetch pair all cameras to start their heartbeats.
        val outcome = prefetchPairCameras(groups) { uuid, preferredName ->
            // Use the existing Hub (scan cache + pair) — the bridge is expected
            // to have already populated the scan cache during user-facing scan.
            // We pass the preferred name as a hint; Hub ignores it but kept for
            // API symmetry with Swift Scanner.pair(uuid:preferredName:).
            @Suppress("UNUSED_PARAMETER")
            val _hint = preferredName
            Insta360BluetoothHub.pair(context, uuid)
        }
        if (outcome.wasCancelled) {
            InstaLog.log(InstaLogCategory.COLLECT, event = "collect_cancelled_during_prefetch")
            return emptyList()
        }

        val results = mutableListOf<Result>()

        // Step 2: per-camera sequential download, wrapped in radio-gate.
        for ((uuid, list) in groups) {
            currentCoroutineContext().ensureActive()
            try {
                val pairControllerResult = runCatching { Insta360BluetoothHub.pair(context, uuid) }
                val controller = pairControllerResult.getOrNull()
                if (controller == null) {
                    val err = pairControllerResult.exceptionOrNull()!!
                    for (p in list) {
                        progress(Progress(p.episodeDir, p.sidecar.streamId, uuid, p.sidecar.role, "failed", 0.0, error = err.message))
                        results += Result(p.episodeDir, p.sidecar.streamId, uuid, filePath = null, error = err)
                    }
                    continue
                }

                // Acquire WiFi credentials
                val credsResult = runCatching { controller.wifiCredentials() }
                if (credsResult.isFailure) {
                    val err = credsResult.exceptionOrNull()!!
                    for (p in list) {
                        progress(Progress(p.episodeDir, p.sidecar.streamId, uuid, p.sidecar.role, "failed", 0.0, error = err.message))
                        results += Result(p.episodeDir, p.sidecar.streamId, uuid, filePath = null, error = err)
                    }
                    continue
                }
                val (ssid, passphrase) = credsResult.getOrThrow()
                enableCameraWifiForDownload(controller, uuid)

                for (p in list) {
                    progress(Progress(p.episodeDir, p.sidecar.streamId, uuid, p.sidecar.role, "pairing", 0.0, ssid = ssid))
                }

                // Build batch items
                val downloader = Insta360WiFiDownloader(context)
                val batchItems = list.map { p ->
                    Insta360WiFiDownloader.BatchItem(
                        episodeDir = p.episodeDir,
                        streamId = p.sidecar.streamId,
                        remoteFileURI = p.sidecar.cameraFileURI,
                        destination = File(p.episodeDir, "${p.sidecar.streamId}.mp4"),
                        bleAckMonotonicNs = p.sidecar.bleAckMonotonicNs,
                        sidecar = p.sidecar,
                    )
                }

                // Wrap download in coordinator's radio gate — heartbeat throttling
                val batchResults = Insta360ConnectionCoordinator.withWiFi(uuid) {
                    for (p in list) {
                        progress(Progress(p.episodeDir, p.sidecar.streamId, uuid, p.sidecar.role, "awaiting_wifi_join", 0.0, ssid = ssid))
                    }
                    downloader.downloadBatch(
                        ssid = ssid,
                        passphrase = passphrase,
                        items = batchItems,
                        onItemStart = { item ->
                            // synchronous callback — we can't suspend here. Log only.
                            InstaLog.log(
                                InstaLogCategory.COLLECT, event = "download_started",
                                fields = mapOf("stream_id" to item.streamId),
                            )
                        },
                        progress = { item, frac ->
                            // also synchronous; cannot call suspend progress
                            // (downloader doesn't support suspending progress callbacks)
                            // Bridge will receive per-completion events below.
                            InstaLog.log(
                                InstaLogCategory.COLLECT, level = InstaLogLevel.DEBUG,
                                event = "download_progress",
                                fields = mapOf("stream_id" to item.streamId, "fraction" to frac),
                            )
                        },
                    )
                }

                // Per-item: emit done/failed + remove pending sidecar on success
                for (batchResult in batchResults) {
                    val item = batchResult.item
                    val pending = list.first { it.sidecar.streamId == item.streamId }
                    if (batchResult.success) {
                        Insta360PendingSidecar.delete(pending.episodeDir, item.streamId)
                        progress(Progress(item.episodeDir, item.streamId, uuid, pending.sidecar.role, "done", 1.0, ssid = ssid))
                        results += Result(item.episodeDir, item.streamId, uuid, filePath = item.destination, error = null)
                        InstaLog.log(
                            InstaLogCategory.WIFI, event = "download_complete",
                            fields = mapOf("stream_id" to item.streamId, "file" to item.destination.absolutePath),
                        )
                    } else {
                        val err = IllegalStateException(batchResult.error ?: "unknown")
                        progress(Progress(item.episodeDir, item.streamId, uuid, pending.sidecar.role, "failed", 0.0, ssid = ssid, error = batchResult.error))
                        results += Result(item.episodeDir, item.streamId, uuid, filePath = null, error = err)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                InstaLog.log(
                    InstaLogCategory.COLLECT, level = InstaLogLevel.WARN,
                    event = "camera_batch_failed",
                    fields = mapOf("uuid" to uuid, "error" to (t.message ?: t::class.java.simpleName)),
                )
                for (p in list) {
                    progress(Progress(p.episodeDir, p.sidecar.streamId, uuid, p.sidecar.role, "failed", 0.0, error = t.message))
                    if (results.none { it.streamId == p.sidecar.streamId }) {
                        results += Result(p.episodeDir, p.sidecar.streamId, uuid, filePath = null, error = t)
                    }
                }
            }
        }

        InstaLog.log(
            InstaLogCategory.COLLECT, event = "collect_finished",
            fields = mapOf(
                "succeeded" to results.count { it.success },
                "failed" to results.count { !it.success },
            ),
        )
        return results
    }

    private suspend fun enableCameraWifiForDownload(
        controller: Insta360BLEController,
        uuid: String,
    ) {
        runCatching { controller.enableWiFiForDownload() }
            .onSuccess {
                InstaLog.log(
                    InstaLogCategory.COLLECT,
                    event = "wifi_enable_for_download_ok",
                    fields = mapOf("uuid" to uuid),
                )
            }
            .onFailure { t ->
                InstaLog.log(
                    InstaLogCategory.COLLECT,
                    level = InstaLogLevel.WARN,
                    event = "wifi_enable_for_download_failed_continue",
                    fields = mapOf(
                        "uuid" to uuid,
                        "error" to (t.message ?: t::class.java.simpleName),
                    ),
                )
            }
    }

    /** Test-only — clears active-job state. */
    internal fun resetForTest() {
        kotlinx.coroutines.runBlocking {
            cancelMutex.withLock {
                activeJob?.cancel()
                activeJob = null
            }
        }
    }
}
