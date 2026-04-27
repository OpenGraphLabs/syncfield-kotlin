package io.opengraph.syncfield

import io.opengraph.syncfield.audio.ChirpSource
import io.opengraph.syncfield.audio.ChirpSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format compatible with `sync_point.json` produced by syncfield-swift.
 * The chirp fields are present only when chirp emission is enabled.
 */
@Serializable
data class SyncPoint(
    @SerialName("sdk_version")        val sdkVersion: String,
    @SerialName("monotonic_ns")       val monotonicNs: Long,
    @SerialName("wall_clock_ns")      val wallClockNs: Long,
    @SerialName("host_id")            val hostId: String,
    @SerialName("iso_datetime")       val isoDatetime: String,
    @SerialName("chirp_start_ns")     val chirpStartNs: Long? = null,
    @SerialName("chirp_stop_ns")      val chirpStopNs: Long? = null,
    @SerialName("chirp_start_source") val chirpStartSource: ChirpSource? = null,
    @SerialName("chirp_stop_source")  val chirpStopSource: ChirpSource? = null,
    @SerialName("chirp_spec")         val chirpSpec: ChirpSpec? = null,
)
