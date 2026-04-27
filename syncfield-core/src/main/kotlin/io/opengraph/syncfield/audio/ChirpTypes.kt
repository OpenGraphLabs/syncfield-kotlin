package io.opengraph.syncfield.audio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Linear FM sweep specification. Wire-format compatible with the iOS SDK.
 *
 * Defaults sit in the 17–19 kHz near-ultrasonic band so the sync chirp
 * doesn't disrupt the user during recording. See `ChirpTypes.swift`
 * comments for the band rationale.
 */
@Serializable
data class ChirpSpec(
    @SerialName("from_hz")     val fromHz: Double,
    @SerialName("to_hz")       val toHz: Double,
    @SerialName("duration_ms") val durationMs: Double,
    @SerialName("amplitude")   val amplitude: Double,
    @SerialName("envelope_ms") val envelopeMs: Double,
) {
    companion object {
        /** Start chirp — rising 17 → 19 kHz, 500 ms. Near-inaudible default. */
        val defaultStart = ChirpSpec(
            fromHz = 17_000.0, toHz = 19_000.0,
            durationMs = 500.0, amplitude = 0.8, envelopeMs = 15.0,
        )

        /** Stop chirp — falling 19 → 17 kHz, 500 ms. Near-inaudible default. */
        val defaultStop = ChirpSpec(
            fromHz = 19_000.0, toHz = 17_000.0,
            durationMs = 500.0, amplitude = 0.8, envelopeMs = 15.0,
        )

        /** Audible legacy start chirp — rising 400 → 2500 Hz, 500 ms. */
        val audibleStart = ChirpSpec(
            fromHz = 400.0, toHz = 2_500.0,
            durationMs = 500.0, amplitude = 0.8, envelopeMs = 15.0,
        )

        /** Audible legacy stop chirp — falling 2500 → 400 Hz, 500 ms. */
        val audibleStop = ChirpSpec(
            fromHz = 2_500.0, toHz = 400.0,
            durationMs = 500.0, amplitude = 0.8, envelopeMs = 15.0,
        )
    }
}

/** Source of a chirp emission. */
@Serializable
enum class ChirpSource {
    @SerialName("hardware")          Hardware,
    @SerialName("software_fallback") SoftwareFallback,
    @SerialName("silent")            Silent,
}

/**
 * Result of [ChirpPlayer.play]. `bestNs` returns hardware-anchored time
 * when available, otherwise the software clock at scheduling time.
 */
data class ChirpEmission(
    val softwareNs: Long,
    val hardwareNs: Long?,
    val source: ChirpSource,
) {
    val bestNs: Long get() = hardwareNs ?: softwareNs
}
