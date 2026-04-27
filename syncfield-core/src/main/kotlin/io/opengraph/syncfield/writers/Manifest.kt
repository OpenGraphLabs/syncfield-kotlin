package io.opengraph.syncfield.writers

import io.opengraph.syncfield.StreamCapabilities
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Wire-format compatible with `manifest.json` produced by syncfield-swift.
 */
@Serializable
data class Manifest(
    @SerialName("sdk_version") val sdkVersion: String,
    @SerialName("host_id")     val hostId: String,
    val role: String,
    val streams: List<StreamEntry>,
) {
    @Serializable
    data class StreamEntry(
        @SerialName("stream_id")    val streamId: String,
        @SerialName("file_path")    val filePath: String,
        @SerialName("frame_count")  val frameCount: Int,
        val kind: String,
        val capabilities: StreamCapabilities,
    )
}

/** Atomic writer for `manifest.json`. */
object ManifestWriter {
    fun write(manifest: Manifest, file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(SyncFieldJson.encodeToString(Manifest.serializer(), manifest))
        if (!tmp.renameTo(file)) {
            // renameTo can fail across filesystems — fall back to copy + delete.
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
