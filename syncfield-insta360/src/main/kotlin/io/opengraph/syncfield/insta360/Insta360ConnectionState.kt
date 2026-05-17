package io.opengraph.syncfield.insta360

/**
 * State machine emitted by `Insta360CameraSupervisor`.
 *
 * Mirrors `Insta360ConnectionState.swift`. Sub-states for phone-auth pairing are
 * surfaced as [CONNECTING] with a refined `Insta360ConnectionHealth.lastError`
 * payload rather than their own case — keeps the RN consumer surface stable.
 */
enum class Insta360ConnectionState(val wireName: String) {
    /** Initial state — no `attach()` has been called for this binding key. */
    IDLE("idle"),

    /** BLE scan window open, looking for an advertisement matching this UUID. */
    SEARCHING("searching"),

    /** Advertisement found, BLE connect in progress (+ readiness probe). */
    CONNECTING("connecting"),

    /** BLE connected, command channel ACK'd at least once, heartbeat firing. */
    BLE_READY("bleReady"),

    /** Connected but degraded: RSSI < threshold or recent heartbeat misses. */
    BLE_DEGRADED("bleDegraded"),

    /**
     * `RadioGate.acquireWiFi` granted. AP-bound camera; heartbeat paused.
     * Other supervised cameras are on slow heartbeat for the lease duration.
     */
    WIFI_BOUND("wifiBound"),

    /**
     * App backgrounded with no active recording. BLE link retained by OS,
     * heartbeat paused. Recovery on foreground entry.
     */
    BLE_SUSPENDED("bleSuspended"),

    /** Unsolicited disconnect; `ReconnectPolicy` backoff schedule running. */
    RECONNECTING("reconnecting"),

    /**
     * Persistent classifier fired (no advertisement for N scan windows, or
     * hard BLE error). Requires user action (power button, undock) or explicit
     * `forceReconnect`.
     */
    LOST("lost"),

    /** `detach()` called or session ended. Terminal — supervisor will not reconnect. */
    GIVE_UP("giveUp");

    /**
     * Whether the supervisor accepts BLE commands while in this state.
     * Used by callers (Bridge, Collector) to decide fast-fail vs await.
     */
    val acceptsCommands: Boolean
        get() = when (this) {
            BLE_READY, BLE_DEGRADED, WIFI_BOUND -> true
            else -> false
        }

    /**
     * Whether transition to this state is terminal (no further automatic
     * recovery without explicit user action).
     */
    val isTerminal: Boolean
        get() = this == LOST || this == GIVE_UP

    companion object {
        fun fromWireName(name: String): Insta360ConnectionState? =
            values().firstOrNull { it.wireName == name }
    }
}
