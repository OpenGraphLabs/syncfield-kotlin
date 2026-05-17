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
