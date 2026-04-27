package io.opengraph.syncfield.writers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Append-only `session.log`. Each call fsyncs before returning so
 * entries survive a process crash. One JSON object per line.
 */
class SessionLogWriter(file: File) {

    private val fileOut = FileOutputStream(file)
    private val mutex = Mutex()

    suspend fun append(kind: String, detail: String) {
        val row = JsonObject(
            sortedMapOf(
                "detail" to JsonPrimitive(detail),
                "kind"   to JsonPrimitive(kind),
                "ts"     to JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(Instant.now())),
            )
        )
        val bytes = (SyncFieldJsonCompact.encodeToString(JsonObject.serializer(), row) + "\n")
            .toByteArray(Charsets.UTF_8)
        mutex.withLock {
            fileOut.write(bytes)
            fileOut.fd.sync()       // fsync on every entry
        }
    }

    fun close() {
        fileOut.fd.sync()
        fileOut.close()
    }
}
