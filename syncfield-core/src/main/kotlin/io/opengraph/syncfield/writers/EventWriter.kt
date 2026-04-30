package io.opengraph.syncfield.writers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream

data class EventHandle internal constructor(internal val id: Long)

/**
 * Append-only JSONL writer for per-episode point and interval events.
 *
 * Open intervals are buffered until they close, keeping each JSONL row
 * complete and independently parseable.
 */
class EventWriter(
    private val file: File,
    private val streamId: String = "cam_ego",
) {
    private data class OpenInterval(
        val kind: String,
        val startMonotonicNs: Long,
        val startFrame: Int,
        val payload: Map<String, Any?>,
    )

    private val mutex = Mutex()
    private var fileOut: FileOutputStream? = null
    private var nextId: Long = 1L
    private val openIntervals: MutableMap<Long, OpenInterval> = mutableMapOf()
    private val pendingLines: MutableList<String> = mutableListOf()

    suspend fun appendIntervalStart(
        kind: String,
        startMonotonicNs: Long,
        startFrame: Int,
        payload: Map<String, Any?>,
    ): EventHandle = mutex.withLock {
        val id = nextId++
        openIntervals[id] = OpenInterval(kind, startMonotonicNs, startFrame, payload)
        EventHandle(id)
    }

    suspend fun closeInterval(
        handle: EventHandle,
        endMonotonicNs: Long,
        endFrame: Int,
    ) = mutex.withLock {
        val open = openIntervals.remove(handle.id) ?: return@withLock
        writeRecordLocked(
            kind = open.kind,
            startMonotonicNs = open.startMonotonicNs,
            endMonotonicNs = endMonotonicNs,
            payload = open.payload,
            extraPayload = mapOf("frame_start" to open.startFrame, "frame_end" to endFrame),
        )
    }

    suspend fun appendPoint(
        kind: String,
        monotonicNs: Long,
        payload: Map<String, Any?>,
    ) = mutex.withLock {
        writeRecordLocked(kind, monotonicNs, monotonicNs, payload, emptyMap())
    }

    suspend fun finalize(stopMonotonicNs: Long, stopFrame: Int) = mutex.withLock {
        val stillOpen = openIntervals.toMap()
        openIntervals.clear()
        for ((_, open) in stillOpen) {
            writeRecordLocked(
                kind = open.kind,
                startMonotonicNs = open.startMonotonicNs,
                endMonotonicNs = stopMonotonicNs,
                payload = open.payload,
                extraPayload = mapOf(
                    "frame_start" to open.startFrame,
                    "frame_end" to stopFrame,
                    "_truncated_at_stop" to true,
                ),
            )
        }
        flushLocked()
        fileOut?.fd?.sync()
        fileOut?.close()
        fileOut = null
    }

    suspend fun flush() = mutex.withLock {
        flushLocked()
    }

    private fun writeRecordLocked(
        kind: String,
        startMonotonicNs: Long,
        endMonotonicNs: Long,
        payload: Map<String, Any?>,
        extraPayload: Map<String, Any?>,
    ) {
        val combined = payload.toMutableMap()
        combined.putAll(extraPayload)
        val record = JsonObject(
            sortedMapOf(
                "end_monotonic_ns" to anyToJson(endMonotonicNs),
                "kind" to anyToJson(kind),
                "payload" to anyToJson(combined),
                "start_monotonic_ns" to anyToJson(startMonotonicNs),
                "stream_id" to anyToJson(streamId),
            )
        )
        pendingLines += SyncFieldJsonCompact.encodeToString(JsonObject.serializer(), record)
    }

    private fun flushLocked() {
        if (pendingLines.isEmpty()) return
        file.parentFile?.mkdirs()
        val out = fileOut ?: FileOutputStream(file, true).also { fileOut = it }
        for (line in pendingLines) {
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
        }
        out.fd.sync()
        pendingLines.clear()
    }
}
