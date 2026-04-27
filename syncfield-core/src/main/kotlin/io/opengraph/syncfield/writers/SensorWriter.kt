package io.opengraph.syncfield.writers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Writes one JSON line per sensor sample into `<streamId>.jsonl`.
 *
 * Each row matches the Swift writer's schema:
 * ```
 * {"frame":N,"timestamp_ns":NS,"channels":{...},"device_timestamp_ns":NS?}
 * ```
 */
class SensorWriter(file: File) {

    private val out = BufferedOutputStream(FileOutputStream(file))
    private val mutex = Mutex()

    @Volatile var count: Int = 0
        private set

    suspend fun append(
        frame: Int,
        monotonicNs: Long,
        channels: Map<String, Any?>,
        deviceTimestampNs: Long? = null,
    ) {
        val sortedChannels: Map<String, JsonElement> = channels.entries
            .sortedBy { it.key }
            .associate { it.key to anyToJson(it.value) }

        val rowBuilder = mutableMapOf<String, JsonElement>(
            "channels"     to JsonObject(sortedChannels),
            "frame"        to JsonPrimitive(frame),
            "timestamp_ns" to JsonPrimitive(monotonicNs),
        )
        if (deviceTimestampNs != null) {
            rowBuilder["device_timestamp_ns"] = JsonPrimitive(deviceTimestampNs)
        }
        val row = JsonObject(rowBuilder.toSortedMap())
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
