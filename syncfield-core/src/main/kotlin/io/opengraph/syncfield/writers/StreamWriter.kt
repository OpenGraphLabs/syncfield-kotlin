package io.opengraph.syncfield.writers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Writes one JSON line per video frame timestamp into
 * `<streamId>.timestamps.jsonl`.
 *
 * Each row matches the Swift writer's schema:
 * ```
 * {"frame_number":N,"capture_ns":NS,"uncertainty_ns":NS}
 * ```
 *
 * `append` is serialised through a coroutine [Mutex] so concurrent
 * writers from a parallel-emit camera pipeline can't tear a row.
 */
class StreamWriter(file: File) {

    private val out = BufferedOutputStream(FileOutputStream(file))
    private val mutex = Mutex()

    @Volatile var count: Int = 0
        private set

    suspend fun append(frame: Int, monotonicNs: Long, uncertaintyNs: Long) {
        val row = JsonObject(
            mapOf(
                "capture_ns"     to JsonPrimitive(monotonicNs),
                "frame_number"   to JsonPrimitive(frame),
                "uncertainty_ns" to JsonPrimitive(uncertaintyNs),
            ).toSortedMap()
        )
        val bytes = (SyncFieldJsonCompact.encodeToString(JsonObject.serializer(), row) + "\n")
            .toByteArray(Charsets.UTF_8)
        mutex.withLock {
            out.write(bytes)
            count += 1
        }
    }

    fun close() {
        out.flush()
        out.close()
    }
}
