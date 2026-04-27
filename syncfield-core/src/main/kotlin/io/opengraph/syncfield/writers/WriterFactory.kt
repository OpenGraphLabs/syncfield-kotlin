package io.opengraph.syncfield.writers

import java.io.File

/**
 * Injected into [io.opengraph.syncfield.SyncFieldStream.startRecording] so the
 * stream creates writers rooted at the episode directory without knowing
 * the directory path.
 */
class WriterFactory(val episodeDirectory: File) {

    fun makeStreamWriter(streamId: String): StreamWriter =
        StreamWriter(File(episodeDirectory, "$streamId.timestamps.jsonl"))

    fun makeSensorWriter(streamId: String): SensorWriter =
        SensorWriter(File(episodeDirectory, "$streamId.jsonl"))

    fun videoFile(streamId: String, extension: String = "mp4"): File =
        File(episodeDirectory, "$streamId.$extension")
}
