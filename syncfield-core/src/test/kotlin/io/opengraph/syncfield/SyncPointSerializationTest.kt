package io.opengraph.syncfield

import com.google.common.truth.Truth.assertThat
import io.opengraph.syncfield.audio.ChirpSource
import io.opengraph.syncfield.audio.ChirpSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class SyncPointSerializationTest {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `bare anchor serialises without chirp fields`() {
        val sp = SyncPoint(
            sdkVersion  = "0.3.0",
            monotonicNs = 12345L,
            wallClockNs = 99L,
            hostId      = "h",
            isoDatetime = "2026-04-26T00:00:00.000Z",
        )
        val out = json.encodeToString(SyncPoint.serializer(), sp)
        assertThat(out).contains("\"sdk_version\": \"0.3.0\"")
        assertThat(out).contains("\"monotonic_ns\": 12345")
        assertThat(out).contains("\"wall_clock_ns\": 99")
        assertThat(out).contains("\"host_id\": \"h\"")
        // Chirp fields are absent because of explicitNulls=false.
        assertThat(out).doesNotContain("chirp_start_ns")
    }

    @Test
    fun `anchor with chirp emits chirp fields`() {
        val sp = SyncPoint(
            sdkVersion  = "0.3.0",
            monotonicNs = 100L,
            wallClockNs = 200L,
            hostId      = "h",
            isoDatetime = "2026-04-26T00:00:00.000Z",
            chirpStartNs    = 150L,
            chirpStartSource = ChirpSource.Hardware,
            chirpSpec       = ChirpSpec.defaultStart,
        )
        val out = json.encodeToString(SyncPoint.serializer(), sp)
        val parsed = json.parseToJsonElement(out).jsonObject
        assertThat(parsed["chirp_start_ns"]?.jsonPrimitive?.content).isEqualTo("150")
        assertThat(parsed["chirp_start_source"]?.jsonPrimitive?.content).isEqualTo("hardware")
    }

    private val kotlinx.serialization.json.JsonElement.jsonObject: JsonObject get() = this as JsonObject
}
