package io.opengraph.syncfield.insta360

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    val streamId: String,
    val bleUuid: String,
    val bleName: String,
    val cameraFileURI: String,
    val bleAckMonotonicNs: Long,
    val role: String = "",
    val savedAt: String = "",
    val stopFailureReason: String? = null,
    val bleAckWallClockMs: Long? = null,
    val stopWallClockMs: Long? = null,
    val cameraDurationSec: Long? = null,
    val cameraFileSize: Long? = null,
    val expectedSegments: Int? = null,
) {
    val needsCameraFileURIResolution: Boolean
        get() = needsCameraFileURIResolution(cameraFileURI)

    companion object {
        const val unresolvedCameraFileURI: String = "unresolved://latest-video"

        fun needsCameraFileURIResolution(uri: String): Boolean =
            uri.trim().lowercase().startsWith("unresolved://")

        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun write(episodeDir: File, sidecar: Insta360PendingSidecar) {
            val file = File(episodeDir, "${sidecar.streamId}.pending.json")
            episodeDir.mkdirs()
            file.writeText(json.encodeToString(serializer(), sidecar))
            deleteTentative(episodeDir, sidecar.streamId)
        }

        fun write(
            episodeDir: File,
            streamId: String,
            cameraFileURI: String,
            bleUuid: String,
            bleName: String,
            role: String,
            bleAckNs: Long,
            stopFailureReason: String? = null,
            bleAckWallClockMs: Long? = null,
            stopWallClockMs: Long? = null,
            cameraDurationSec: Long? = null,
            cameraFileSize: Long? = null,
            expectedSegments: Int? = null,
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
                stopFailureReason = stopFailureReason,
                bleAckWallClockMs = bleAckWallClockMs,
                stopWallClockMs = stopWallClockMs,
                cameraDurationSec = cameraDurationSec,
                cameraFileSize = cameraFileSize,
                expectedSegments = expectedSegments,
            ),
        )

        fun writeUnresolved(
            episodeDir: File,
            streamId: String,
            bleUuid: String,
            bleName: String,
            role: String,
            bleAckNs: Long,
            stopFailureReason: String,
            bleAckWallClockMs: Long? = null,
            stopWallClockMs: Long? = null,
            cameraDurationSec: Long? = null,
            cameraFileSize: Long? = null,
            expectedSegments: Int? = null,
        ) = write(
            episodeDir = episodeDir,
            streamId = streamId,
            cameraFileURI = unresolvedCameraFileURI,
            bleUuid = bleUuid,
            bleName = bleName,
            role = role,
            bleAckNs = bleAckNs,
            stopFailureReason = stopFailureReason,
            bleAckWallClockMs = bleAckWallClockMs,
            stopWallClockMs = stopWallClockMs,
            cameraDurationSec = cameraDurationSec,
            cameraFileSize = cameraFileSize,
            expectedSegments = expectedSegments,
        )

        fun writeTentative(
            episodeDir: File,
            streamId: String,
            role: String,
            deviceUuid: String,
            deviceName: String,
            bleAckNs: Long,
        ) {
            val file = File(episodeDir, "$streamId.pending.tentative.json")
            val sidecar = JsonObject(
                sortedMapOf(
                    "bleAckMonotonicNs" to JsonPrimitive(bleAckNs),
                    "deviceName" to JsonPrimitive(deviceName),
                    "deviceUuid" to JsonPrimitive(deviceUuid),
                    "expectedSetupType" to JsonPrimitive("ego_wrist"),
                    "role" to JsonPrimitive(role),
                    "sessionStartIso" to JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(Instant.now())),
                    "streamId" to JsonPrimitive(streamId),
                )
            )
            episodeDir.mkdirs()
            file.writeText(json.encodeToString(JsonObject.serializer(), sidecar))
        }

        /** All pending sidecars in a single episode directory. */
        fun scan(episodeDir: File): List<Insta360PendingSidecar> {
            val files = episodeDir.listFiles { _, name -> name.endsWith(".pending.json") }
                ?: return emptyList()
            return files.mapNotNull { f ->
                decode(f.readText())
            }
        }

        /** Pending sidecars across every episode directory rooted at [root]. */
        fun scanRecursive(root: File): List<EpisodePending> {
            if (!root.exists()) return emptyList()
            return root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".pending.json") }
                .mapNotNull { file ->
                    runCatching {
                        val sidecar = decode(file.readText()) ?: return@runCatching null
                        EpisodePending(file.parentFile ?: root, sidecar)
                    }.getOrNull()
                }
                .toList()
        }

        fun delete(episodeDir: File, streamId: String): Boolean =
            File(episodeDir, "$streamId.pending.json").delete()

        fun deleteTentative(episodeDir: File, streamId: String): Boolean =
            File(episodeDir, "$streamId.pending.tentative.json").delete()

        private fun decode(raw: String): Insta360PendingSidecar? {
            runCatching { json.decodeFromString(serializer(), raw) }
                .getOrNull()
                ?.takeIf { it.streamId.isNotBlank() && it.cameraFileURI.isNotBlank() }
                ?.let { return it }

            return runCatching {
                val obj = json.parseToJsonElement(raw).jsonObject
                Insta360PendingSidecar(
                    streamId = obj.string("streamId", "stream_id") ?: return@runCatching null,
                    bleUuid = obj.string("bleUuid", "ble_uuid").orEmpty(),
                    bleName = obj.string("bleName", "ble_name").orEmpty(),
                    cameraFileURI = obj.string("cameraFileURI", "camera_file_uri").orEmpty(),
                    bleAckMonotonicNs = obj.long("bleAckMonotonicNs", "ble_ack_monotonic_ns") ?: 0L,
                    role = obj.string("role").orEmpty(),
                    savedAt = obj.string("savedAt", "saved_at").orEmpty(),
                    stopFailureReason = obj.string("stopFailureReason", "stop_failure_reason"),
                    bleAckWallClockMs = obj.long("bleAckWallClockMs", "ble_ack_wall_clock_ms"),
                    stopWallClockMs = obj.long("stopWallClockMs", "stop_wall_clock_ms"),
                    cameraDurationSec = obj.long("cameraDurationSec", "camera_duration_sec"),
                    cameraFileSize = obj.long("cameraFileSize", "camera_file_size"),
                    expectedSegments = obj.long("expectedSegments", "expected_segments")?.toInt(),
                )
            }.getOrNull()
        }

        private fun JsonObject.string(vararg keys: String): String? {
            for (key in keys) {
                val value = this[key]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                if (value != null) return value
            }
            return null
        }

        private fun JsonObject.long(vararg keys: String): Long? {
            for (key in keys) {
                val primitive = this[key]?.jsonPrimitive ?: continue
                primitive.contentOrNull?.toLongOrNull()?.let { return it }
            }
            return null
        }
    }

    /** A pending sidecar paired with the episode directory it lives in. */
    data class EpisodePending(val episodeDir: File, val sidecar: Insta360PendingSidecar)
}
