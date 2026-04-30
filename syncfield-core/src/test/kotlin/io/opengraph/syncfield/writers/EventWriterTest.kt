package io.opengraph.syncfield.writers

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `interval close writes one complete record`() = runTest {
        val file = File(tmp.root, "events.jsonl")
        val writer = EventWriter(file)
        val handle = writer.appendIntervalStart(
            kind = "hand_out_of_frame",
            startMonotonicNs = 1_000_000_000L,
            startFrame = 10,
            payload = mapOf("hand" to "left"),
        )

        writer.closeInterval(handle, endMonotonicNs = 2_000_000_000L, endFrame = 20)
        writer.flush()

        val line = file.readLines().single()
        val json = Json.parseToJsonElement(line).jsonObject
        assertThat(json["kind"]?.jsonPrimitive?.content).isEqualTo("hand_out_of_frame")
        assertThat(json["start_monotonic_ns"]?.jsonPrimitive?.long).isEqualTo(1_000_000_000L)
        assertThat(json["end_monotonic_ns"]?.jsonPrimitive?.long).isEqualTo(2_000_000_000L)
        assertThat(json["stream_id"]?.jsonPrimitive?.content).isEqualTo("cam_ego")

        val payload = json["payload"]!!.jsonObject
        assertThat(payload["hand"]?.jsonPrimitive?.content).isEqualTo("left")
        assertThat(payload["frame_start"]?.jsonPrimitive?.long).isEqualTo(10L)
        assertThat(payload["frame_end"]?.jsonPrimitive?.long).isEqualTo(20L)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `point event writes start equal to end`() = runTest {
        val file = File(tmp.root, "events.jsonl")
        val writer = EventWriter(file)

        writer.appendPoint(
            kind = "audio_cue_route_set",
            monotonicNs = 5_000_000_000L,
            payload = mapOf("route" to "Bluetooth"),
        )
        writer.flush()

        val json = Json.parseToJsonElement(file.readLines().single()).jsonObject
        assertThat(json["start_monotonic_ns"]?.jsonPrimitive?.long).isEqualTo(5_000_000_000L)
        assertThat(json["end_monotonic_ns"]?.jsonPrimitive?.long).isEqualTo(5_000_000_000L)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `finalize truncates open intervals`() = runTest {
        val file = File(tmp.root, "events.jsonl")
        val writer = EventWriter(file)
        writer.appendIntervalStart(
            kind = "hand_out_of_frame",
            startMonotonicNs = 1_000_000_000L,
            startFrame = 10,
            payload = mapOf("hand" to "left"),
        )

        writer.finalize(stopMonotonicNs = 9_999_999_999L, stopFrame = 99)

        val json = Json.parseToJsonElement(file.readLines().single()).jsonObject
        assertThat(json["end_monotonic_ns"]?.jsonPrimitive?.long).isEqualTo(9_999_999_999L)
        val payload = json["payload"]!!.jsonObject
        assertThat(payload["_truncated_at_stop"]?.jsonPrimitive?.boolean).isTrue()
        assertThat(payload["frame_end"]?.jsonPrimitive?.long).isEqualTo(99L)
    }
}
