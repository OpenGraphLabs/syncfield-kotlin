package io.opengraph.syncfield

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process event bus for stream lifecycle signals.
 *
 * Slow subscribers don't apply back-pressure — the buffer keeps the
 * 64 most recent events and drops the oldest if a consumer falls behind.
 */
class HealthBus {
    private val _events = MutableSharedFlow<HealthEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<HealthEvent> = _events.asSharedFlow()

    suspend fun publish(event: HealthEvent) {
        _events.emit(event)
    }
}
