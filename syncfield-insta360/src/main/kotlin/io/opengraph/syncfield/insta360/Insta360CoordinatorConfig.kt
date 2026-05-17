package io.opengraph.syncfield.insta360

import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogLevel

/**
 * Runtime configuration knobs for the Insta360 connection coordinator family.
 *
 * Hosts (og-skill `MainApplication.kt`) set these at process start. The whole
 * surface is mutable so dev builds can toggle subsystems on/off while running
 * the A-S1–A-S14 scenario matrix in `docs/insta360-android-scenario-runbook.md`.
 *
 * **Default values mirror the release-build defaults.** In dev builds prefer
 * [enableScenarioMode] which switches to a verbose log floor.
 *
 * Mirrors `Insta360CoordinatorConfig.swift`.
 */
object Insta360CoordinatorConfig {

    enum class BleStrategyDuringWifi(val wireName: String) {
        /** Keep BLE link to the AP-bound camera; pause heartbeat only. */
        KEEP_ALIVE("keepAlive"),

        /** Explicitly disconnect before WiFi join, reconnect after. */
        DISCONNECT("disconnect");

        companion object {
            fun fromWireName(name: String): BleStrategyDuringWifi? =
                values().firstOrNull { it.wireName == name }
        }
    }

    // --- Connection coordinator toggles -------------------------------------

    /**
     * Drive Supervisor auto-reconnect on unsolicited disconnect. When false,
     * the bridge falls back to the legacy reactive reconnect on foreground.
     */
    @Volatile var autoReconnectEnabled: Boolean = true

    /**
     * Wrap `Insta360WiFiDownloader.downloadBatch` + `listFiles` in
     * `RadioGate.withWiFi` so cross-camera BLE heartbeats throttle while
     * another camera holds the phone WiFi radio.
     */
    @Volatile var radioGateEnabled: Boolean = true

    /**
     * Use `Insta360BackgroundSupervisor` (ProcessLifecycleOwner) to drive
     * BLE_SUSPENDED transitions. When false, the bridge handles fg/bg directly.
     */
    @Volatile var backgroundBLEEnabled: Boolean = true

    /**
     * Emit `syncfield:insta360WakeStallRequiresUser` when wake escalation
     * exceeds [wakeStallThresholdSeconds].
     */
    @Volatile var wakeUserPromptEnabled: Boolean = true

    /** Default 2 s. RadioGate switches to [radioGateSlowHeartbeatIntervalMs] for non-AP-bound cameras. */
    @Volatile var heartbeatIntervalMs: Long = 2_000L

    @Volatile var radioGateSlowHeartbeatIntervalMs: Long = 8_000L

    @Volatile var bleStrategyDuringWifi: BleStrategyDuringWifi = BleStrategyDuringWifi.KEEP_ALIVE

    /** Wake stall threshold before emitting a user prompt. 12 s by default. */
    @Volatile var wakeStallThresholdSeconds: Double = 12.0

    /** Persistent classifier: scan windows yielding no advertisement before transitioning to LOST. */
    @Volatile var persistentScanWindowCount: Int = 3

    @Volatile var persistentScanWindowSeconds: Double = 6.0

    /** Beyond this idle period without any advertisement, classify as persistent. */
    @Volatile var persistentLastSeenThresholdSeconds: Double = 90.0

    // --- Diagnostic surface -------------------------------------------------

    /** Stream `syncfield:insta360HealthSnapshot` to RN every [healthSnapshotIntervalMs] ms. */
    @Volatile var diagnosticsEnabled: Boolean = false

    @Volatile var healthSnapshotIntervalMs: Long = 5_000L

    // --- Logging ------------------------------------------------------------

    /** Applied to `InstaLog.minLevel` via [apply]. Scenario mode forces DEBUG. */
    @Volatile var minLogLevel: InstaLogLevel = InstaLogLevel.INFO

    /** True while the host has explicitly opted into scenario mode. */
    @Volatile var scenarioMode: Boolean = false
        private set

    /**
     * Convenience: switch to "scenario runbook" defaults — verbose Insta360
     * logs. Hosts call this from debug `MainApplication.onCreate()` to set up
     * before launching.
     */
    fun enableScenarioMode() {
        scenarioMode = true
        minLogLevel = InstaLogLevel.DEBUG
        InstaLog.minLevel = InstaLogLevel.DEBUG
    }

    /** Apply the active log level to the global `InstaLog`. */
    fun apply() {
        InstaLog.minLevel = minLogLevel
    }

    /**
     * Reset all knobs to release-build defaults. Test-only entry point — used
     * by `setUp` in unit tests so config state doesn't leak across cases.
     */
    internal fun resetForTest() {
        autoReconnectEnabled = true
        radioGateEnabled = true
        backgroundBLEEnabled = true
        wakeUserPromptEnabled = true
        heartbeatIntervalMs = 2_000L
        radioGateSlowHeartbeatIntervalMs = 8_000L
        bleStrategyDuringWifi = BleStrategyDuringWifi.KEEP_ALIVE
        wakeStallThresholdSeconds = 12.0
        persistentScanWindowCount = 3
        persistentScanWindowSeconds = 6.0
        persistentLastSeenThresholdSeconds = 90.0
        diagnosticsEnabled = false
        healthSnapshotIntervalMs = 5_000L
        minLogLevel = InstaLogLevel.INFO
        scenarioMode = false
        InstaLog.minLevel = InstaLogLevel.INFO
    }
}
