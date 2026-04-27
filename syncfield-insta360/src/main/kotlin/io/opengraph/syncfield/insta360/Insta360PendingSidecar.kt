package io.opengraph.syncfield.insta360

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

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
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun write(episodeDir: File, sidecar: Insta360PendingSidecar) {
            val file = File(episodeDir, "${sidecar.streamId}.pending.json")
            file.writeText(json.encodeToString(serializer(), sidecar))
        }

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
            val episodes = root.listFiles { f -> f.isDirectory && f.name.startsWith("ep_") }
                ?: return emptyList()
            return episodes.flatMap { ep -> scan(ep).map { EpisodePending(ep, it) } }
        }

        fun delete(episodeDir: File, streamId: String): Boolean =
            File(episodeDir, "$streamId.pending.json").delete()
    }

    /** A pending sidecar paired with the episode directory it lives in. */
    data class EpisodePending(val episodeDir: File, val sidecar: Insta360PendingSidecar)
}
