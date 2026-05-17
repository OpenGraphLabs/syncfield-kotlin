package io.opengraph.syncfield.insta360

/**
 * Dock-state hint surfaced via the camera's charge-box status read. Used by
 * [Insta360CameraSupervisor] to choose between `powerButton` and
 * `removeFromDock` wake-stall suggestions.
 */
enum class Insta360DockStatus(val wireName: String) {
    UNKNOWN("unknown"),
    DOCKED("docked"),
    UNDOCKED("undocked");

    companion object {
        fun fromWireName(name: String): Insta360DockStatus? =
            values().firstOrNull { it.wireName == name }
    }
}

/**
 * Health snapshot for a single supervised camera.
 *
 * Emitted via:
 *  - `Insta360CameraSupervisor.health: StateFlow<Insta360ConnectionHealth>` (always current)
 *  - `Insta360ConnectionCoordinator.allHealth()` (snapshot of every attached binding key)
 *  - `syncfield:insta360HealthSnapshot` RN event (5 s ticker when diagnostics on)
 *
 * Mirrors `Insta360ConnectionHealth.swift`.
 *
 * **All fields are immutable** — supervisors emit a new instance on every change
 * so observers can rely on referential equality for `distinctUntilChanged`.
 */
data class Insta360ConnectionHealth(
    val bindingKey: String,
    val role: String? = null,
    val state: Insta360ConnectionState = Insta360ConnectionState.IDLE,
    val rssi: Int? = null,
    val lastSeenAtMs: Long? = null,
    val lastStateChangeAtMs: Long? = null,
    val lastCommandSuccessAtMs: Long? = null,
    val lastError: String? = null,
    val lastErrorAtMs: Long? = null,
    val consecutiveHeartbeatMisses: Int = 0,
    val consecutiveScanWindowsWithoutAdvert: Int = 0,
    val reconnectAttempt: Int = 0,
    val nextReconnectInMs: Long? = null,
    val connectAttemptsThisSession: Int = 0,
    val wifiInFlight: Boolean = false,
    val dockHint: Insta360DockStatus = Insta360DockStatus.UNKNOWN,
    val dockHintLastUpdatedAtMs: Long? = null,
    val batteryPercent: Int? = null,
    val batteryCharging: Boolean? = null,
    val storageRemainingMb: Int? = null,
) {
    /**
     * RN-serializable representation. Keys are camelCase to match iOS bridge
     * output exactly (JS subscribers consume both platforms identically).
     */
    fun toMap(): Map<String, Any?> = mapOf(
        "bindingKey" to bindingKey,
        "role" to role,
        "state" to state.wireName,
        "rssi" to rssi,
        "lastSeenAtMs" to lastSeenAtMs,
        "lastStateChangeAtMs" to lastStateChangeAtMs,
        "lastCommandSuccessAtMs" to lastCommandSuccessAtMs,
        "lastError" to lastError,
        "lastErrorAtMs" to lastErrorAtMs,
        "consecutiveHeartbeatMisses" to consecutiveHeartbeatMisses,
        "consecutiveScanWindowsWithoutAdvert" to consecutiveScanWindowsWithoutAdvert,
        "reconnectAttempt" to reconnectAttempt,
        "nextReconnectInMs" to nextReconnectInMs,
        "connectAttemptsThisSession" to connectAttemptsThisSession,
        "wifiInFlight" to wifiInFlight,
        "dockHint" to dockHint.wireName,
        "dockHintLastUpdatedAtMs" to dockHintLastUpdatedAtMs,
        "batteryPercent" to batteryPercent,
        "batteryCharging" to batteryCharging,
        "storageRemainingMb" to storageRemainingMb,
    )
}
