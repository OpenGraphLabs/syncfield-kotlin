package io.opengraph.syncfield.tactile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class TactileSide {
    @SerialName("left")  Left,
    @SerialName("right") Right,
}

object TactileConstants {
    val SERVICE_UUID:     UUID = UUID.fromString("4652535F-424C-4500-0000-000000000001")
    val SENSOR_CHAR_UUID: UUID = UUID.fromString("4652535F-424C-4500-0001-000000000001")
    val CONFIG_CHAR_UUID: UUID = UUID.fromString("4652535F-424C-4500-0002-000000000001")
    /** GATT client-config descriptor (CCCD) — required to enable notifications. */
    val CCCD_UUID:        UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val NAME_FILTER = "oglo"
    val CANONICAL_FINGER_ORDER = listOf("thumb", "index", "middle", "ring", "pinky")
    const val PACKET_HEADER_BYTES = 6
    const val CHANNELS_PER_SAMPLE = 5
    const val BYTES_PER_SAMPLE = CHANNELS_PER_SAMPLE * 2  // 10
    const val SAMPLE_INTERVAL_US: Long = 10_000           // 100 Hz
}

@Serializable
data class DeviceManifest(
    val device: String,
    val side: TactileSide,
    @SerialName("hw_rev")  val hwRev: String? = null,
    @SerialName("rate_hz") val rateHz: Int,
    val channels: List<Channel>,
) {
    @Serializable
    data class Channel(
        val id: Int,
        val loc: String,
        val type: String,
        val bits: Int,
    )

    fun locationForChannel(id: Int): String? =
        channels.firstOrNull { it.id == id }?.loc
}
