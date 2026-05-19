package io.opengraph.syncfield

/**
 * Lifecycle signals emitted by streams during a session. Subscribers
 * receive these via [HealthBus.events].
 */
sealed interface HealthEvent {
    val streamId: String

    data class StreamConnected(override val streamId: String) : HealthEvent

    data class StreamDisconnected(
        override val streamId: String,
        val reason: String,
    ) : HealthEvent

    data class SamplesDropped(
        override val streamId: String,
        val count: Int,
    ) : HealthEvent

    data class IngestProgress(
        override val streamId: String,
        val fraction: Double,
    ) : HealthEvent

    data class IngestFailed(
        override val streamId: String,
        val error: Throwable,
    ) : HealthEvent

    // --- Audio-interruption health events (v0.7.1, additive) ---
    //
    // Emitted by AndroidCameraStream when CameraX's
    // RecordingStats.audioStats.audioState transitions across the
    // ACTIVE / SOURCE_SILENCED / SOURCE_ERROR / ENCODER_ERROR boundary
    // during a recording. Mirrors syncfield-swift's iPhoneCameraStream
    // audio watchdog so JS hosts see the same shape on both platforms.
    //
    // The SDK does NOT attempt active recovery on interruption — these
    // events are observational only. Hosts decide whether to surface a
    // UI banner, log telemetry, or guide the user to restart.

    /** Audio capture has gone silent on a stream that previously had active audio. */
    data class AudioStalled(
        override val streamId: String,
        val silentForSeconds: Double,
    ) : HealthEvent

    /** Audio capture has returned to active after a prior stall on the same stream. */
    data class AudioRecovered(
        override val streamId: String,
    ) : HealthEvent

    // --- Insta360-specific health events (v0.5.0, additive) ---

    /** Connection state transition emitted by `Insta360CameraSupervisor`. */
    data class Insta360StateTransition(
        override val streamId: String,
        val bindingKey: String,
        val from: String,
        val to: String,
        val reason: String?,
    ) : HealthEvent

    /** Wake-stall threshold crossed; user action required. */
    data class Insta360WakeStallRequiresUser(
        override val streamId: String,
        val bindingKey: String,
        val suggested: String, // "powerButton" | "removeFromDock"
    ) : HealthEvent

    /** Soft warning from the camera (battery, storage, thermal, capture-stop). */
    data class Insta360CameraWarning(
        override val streamId: String,
        val kind: String, // "batteryLow" | "storageLow" | "thermalElevated" | "captureStopped"
        val deviceName: String,
        val level: Int? = null,
        val availableMb: Int? = null,
        val severity: String? = null,
    ) : HealthEvent

    /** Heartbeat status snapshot (ack / miss counter). */
    data class Insta360HeartbeatStatus(
        override val streamId: String,
        val bindingKey: String,
        val consecutiveMisses: Int,
        val rssi: Int?,
    ) : HealthEvent
}
