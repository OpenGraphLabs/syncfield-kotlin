package io.opengraph.syncfield.insta360

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Persisted "we owe this episode an Insta360 download" record. Written
 * after `stopRecording` and consumed later by the bridge module's
 * `collectAllPendingEpisodes` flow when the user docks the camera and
 * triggers a batch collect.
 *
 * Wire-format compatible with the Swift `Insta360PendingSidecar` —
 * `<streamId>.pending.json` is read by the same Python pipeline tooling
 * regardless of which platform recorded it.
 */
@Serializable
data class Insta360PendingSidecar(
    @SerialName("stream_id")             val streamId: String,
    @SerialName("ble_uuid")              val bleUuid: String,
    @SerialName("ble_name")              val bleName: String,
    @SerialName("camera_file_uri")       val cameraFileURI: String,
    @SerialName("ble_ack_monotonic_ns")  val bleAckMonotonicNs: Long,
    val role: String = "",
    @SerialName("saved_at")              val savedAt: String = "",
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun write(episodeDir: File, sidecar: Insta360PendingSidecar) {
            val file = File(episodeDir, "${sidecar.streamId}.pending.json")
            episodeDir.mkdirs()
            file.writeText(json.encodeToString(serializer(), sidecar))
        }

        fun write(
            episodeDir: File,
            streamId: String,
            cameraFileURI: String,
            bleUuid: String,
            bleName: String,
            role: String,
            bleAckNs: Long,
        ) = write(
            episodeDir,
            Insta360PendingSidecar(
                streamId = streamId,
                bleUuid = bleUuid,
                bleName = bleName,
                role = role,
                cameraFileURI = cameraFileURI,
                bleAckMonotonicNs = bleAckNs,
                savedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            ),
        )

        /** All pending sidecars in a single episode directory. */
        fun scan(episodeDir: File): List<Insta360PendingSidecar> {
            val files = episodeDir.listFiles { _, name -> name.endsWith(".pending.json") }
                ?: return emptyList()
            return files.mapNotNull { f ->
                runCatching { json.decodeFromString(serializer(), f.readText()) }
                    .getOrNull()
            }
        }

        /** Pending sidecars across every episode directory rooted at [root]. */
        fun scanRecursive(root: File): List<EpisodePending> {
            if (!root.exists()) return emptyList()
            return root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".pending.json") }
                .mapNotNull { file ->
                    runCatching {
                        val sidecar = json.decodeFromString(serializer(), file.readText())
                        EpisodePending(file.parentFile ?: root, sidecar)
                    }.getOrNull()
                }
                .toList()
        }

        fun delete(episodeDir: File, streamId: String): Boolean =
            File(episodeDir, "$streamId.pending.json").delete()
    }

    /** A pending sidecar paired with the episode directory it lives in. */
    data class EpisodePending(val episodeDir: File, val sidecar: Insta360PendingSidecar)
}
