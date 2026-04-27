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
}
