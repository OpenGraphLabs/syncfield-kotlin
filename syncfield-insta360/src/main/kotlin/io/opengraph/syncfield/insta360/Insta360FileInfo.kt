package io.opengraph.syncfield.insta360

/**
 * Metadata for a single file on the Insta360 camera, as listed via `getCameraFileList`.
 *
 * Mirrors `Insta360FileInfo.swift`. RN-serializable via `toMap()`.
 */
data class Insta360FileInfo(
    val fileUri: String,
    val createdAtIso: String,
    val durationSec: Double,
    val sizeBytes: Long,
    val thumbnailUri: String? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "fileUri" to fileUri,
        "createdAtIso" to createdAtIso,
        "durationSec" to durationSec,
        "sizeBytes" to sizeBytes,
        "thumbnailUri" to thumbnailUri,
    )
}
