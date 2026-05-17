package io.opengraph.syncfield.insta360

import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Suggested user action when the wake stall threshold fires. */
enum class Insta360WakeStallSuggestedAction(val wireName: String) {
    POWER_BUTTON("powerButton"),
    REMOVE_FROM_DOCK("removeFromDock");
}

/** State-transition event emitted by [Insta360CameraSupervisor.transitions]. */
data class Insta360TransitionEvent(
    val bindingKey: String,
    val role: String?,
    val from: Insta360ConnectionState,
    val to: Insta360ConnectionState,
    val reason: String?,
    val health: Insta360ConnectionHealth,
)

/** Wake-stall prompt emitted by [Insta360CameraSupervisor.wakeStalls]. */
data class Insta360WakeStallEvent(
    val bindingKey: String,
    val role: String?,
    val suggested: Insta360WakeStallSuggestedAction,
    val health: Insta360ConnectionHealth,
)

/**
 * External input events the supervisor consumes. Keeping the surface
 * explicit (rather than wiring SDK callbacks directly into the supervisor)
 * means we can drive the state machine from a unit test with zero hardware.
 *
 * Mirrors `Insta360SupervisorEvent` in `Insta360CameraSupervisor.swift`.
 */
sealed class Insta360SupervisorEvent {
    object Attached : Insta360SupervisorEvent()
    object PairAttemptStarted : Insta360SupervisorEvent()
    object RecordingReadinessFailed : Insta360SupervisorEvent()
    data class ScanWindowOpened(val durationMs: Long) : Insta360SupervisorEvent()
    data class ScanHit(val rssi: Int?) : Insta360SupervisorEvent()
    object ScanWindowClosedNoHit : Insta360SupervisorEvent()
    data class WakeCycleStarted(val strategy: String) : Insta360SupervisorEvent()
    object ReadinessProbeStarted : Insta360SupervisorEvent()
    data class ReadinessProbeAck(val elapsedMs: Long) : Insta360SupervisorEvent()
    data class ReadinessProbeFailed(val error: String) : Insta360SupervisorEvent()
    data class ConnectFailed(val error: String) : Insta360SupervisorEvent()
    data class HeartbeatAck(val rssi: Int?) : Insta360SupervisorEvent()
    object HeartbeatMiss : Insta360SupervisorEvent()
    data class RssiSample(val rssi: Int) : Insta360SupervisorEvent()
    data class UnsolicitedDisconnect(val error: String?) : Insta360SupervisorEvent()
    object WifiAcquired : Insta360SupervisorEvent()
    object WifiReleased : Insta360SupervisorEvent()
    data class BackgroundEntered(val recordingActive: Boolean) : Insta360SupervisorEvent()
    object ForegroundEntered : Insta360SupervisorEvent()
    object ForceReconnectRequested : Insta360SupervisorEvent()
    data class DockPolled(val status: Insta360DockStatus) : Insta360SupervisorEvent()
    data class BatteryPolled(val percent: Int?, val charging: Boolean?) : Insta360SupervisorEvent()
    object Detached : Insta360SupervisorEvent()
}

/**
 * Driver invoked by the supervisor's auto-reconnect path.
 *
 * Real coordinator passes a closure that invokes BLE scan + connect; tests
 * pass a stub. Throwing `user_action_required` (substring match) signals
 * the failure can't be auto-recovered and transitions to LOST without
 * burning further backoff cycles.
 */
typealias Insta360ReconnectDriver = suspend () -> Unit

/**
 * Per-camera state-machine actor.
 *
 * Holds [Insta360ConnectionHealth], drives transitions in response to
 * [Insta360SupervisorEvent] inputs, emits transitions + wake stalls via
 * SharedFlow, and (when [reconnectDriver] is supplied) schedules a reconnect
 * Job on unsolicited disconnect.
 *
 * Ported verbatim from `Insta360CameraSupervisor.swift`. All the load-bearing
 * invariants are preserved including:
 *
 * - 500 ms debounce on duplicate `UnsolicitedDisconnect` events (SDK fires
 *   the same drop through multiple paths within ms)
 * - Wake stall threshold (12 s default) gated to `SEARCHING`/`CONNECTING`
 *   states only — modal storms from background wake cycles are filtered out
 * - Persistent classifier checks (empty scan windows, hard error string,
 *   advertisement age) consulted from both `scanWindowClosedNoHit` and
 *   reconnect failures
 * - `ForceReconnectRequested` full reset (wake counters AND backoff attempt)
 *   so repeated user taps stay fast at 500 ms
 *
 * Threading: events are serialized through [eventMutex]; the supervisor's
 * own coroutine scope owns [reconnectJob] and is canceled by [Insta360SupervisorEvent.Detached].
 */
class Insta360CameraSupervisor(
    val bindingKey: String,
    val role: String?,
    val policy: Insta360ReconnectPolicy = Insta360ReconnectPolicy(),
    val config: Insta360CoordinatorConfig = Insta360CoordinatorConfig,
    parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val reconnectDriver: Insta360ReconnectDriver? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Standalone job (NOT a child of [parentScope]) so [runTest]-driven tests
     * complete cleanly without explicit teardown. The supervisor still uses
     * [parentScope]'s dispatcher when present, so test virtual-time advances
     * drive scheduled reconnects correctly.
     */
    private val supervisorJob = SupervisorJob()
    private val supervisorScope =
        CoroutineScope(
            supervisorJob + (parentScope.coroutineContext[kotlinx.coroutines.CoroutineDispatcher] ?: Dispatchers.Default)
        )

    /**
     * Cancel all supervisor coroutines (heartbeat tickers, scheduled reconnects).
     * Idempotent. Tests can call this explicitly; production callers reach the
     * same state via [Insta360SupervisorEvent.Detached].
     */
    fun shutdown() {
        supervisorJob.cancel()
    }
    private val eventMutex = Mutex()

    private val _health = MutableStateFlow(Insta360ConnectionHealth(bindingKey, role))
    val health: StateFlow<Insta360ConnectionHealth> = _health.asStateFlow()

    private val _transitions = MutableSharedFlow<Insta360TransitionEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val transitions: SharedFlow<Insta360TransitionEvent> = _transitions.asSharedFlow()

    private val _wakeStalls = MutableSharedFlow<Insta360WakeStallEvent>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val wakeStalls: SharedFlow<Insta360WakeStallEvent> = _wakeStalls.asSharedFlow()

    // --- Internal bookkeeping ----------------------------------------------

    private var reconnectAttempt: Int = 0
    private var consecutiveEmptyScanWindows: Int = 0
    private var wakeAttemptStartedAtMs: Long? = null
    private var wakeStallPromptEmitted: Boolean = false
    private var reconnectJob: Job? = null

    /**
     * Timestamp of the most recently processed `UnsolicitedDisconnect` event.
     * Set BEFORE any suspending call so duplicate events queued behind the
     * first one observe the marker and short-circuit. Mirrors the Swift
     * `lastUnsolicitedDisconnectAtMs` field.
     */
    private var lastUnsolicitedDisconnectAtMs: Long? = null

    /** Synchronous health snapshot — bypasses the StateFlow buffer. */
    suspend fun snapshot(): Insta360ConnectionHealth = eventMutex.withLock { _health.value }

    /** Single funnel for all external events. */
    suspend fun handle(event: Insta360SupervisorEvent) = eventMutex.withLock {
        InstaLog.log(
            InstaLogCategory.SUP, role = role, level = InstaLogLevel.DEBUG,
            event = "event_received",
            fields = mapOf("event" to event::class.java.simpleName),
        )
        when (event) {
            Insta360SupervisorEvent.Attached -> transition(Insta360ConnectionState.SEARCHING, "attached")

            Insta360SupervisorEvent.RecordingReadinessFailed -> {
                val suggested = if (_health.value.dockHint == Insta360DockStatus.DOCKED) {
                    Insta360WakeStallSuggestedAction.REMOVE_FROM_DOCK
                } else {
                    Insta360WakeStallSuggestedAction.POWER_BUTTON
                }
                InstaLog.log(
                    InstaLogCategory.WAKE, role = role, level = InstaLogLevel.STATE,
                    event = "wake_user_prompt_emitted",
                    fields = mapOf(
                        "source" to "recording_readiness_failed",
                        "suggested_action" to suggested.wireName,
                    ),
                )
                emitWakeStall(suggested)
                wakeAttemptStartedAtMs = null
                wakeStallPromptEmitted = false
                consecutiveEmptyScanWindows = 0
                if (!_health.value.state.isTerminal) {
                    transition(Insta360ConnectionState.LOST, "recording_readiness_failed")
                }
            }

            Insta360SupervisorEvent.PairAttemptStarted -> {
                consecutiveEmptyScanWindows = 0
                val st = _health.value.state
                if (st != Insta360ConnectionState.SEARCHING && st != Insta360ConnectionState.GIVE_UP) {
                    transition(Insta360ConnectionState.SEARCHING, "pair_attempt_started")
                }
                InstaLog.log(
                    InstaLogCategory.SUP, role = role, event = "pair_attempt_started",
                    fields = mapOf("wake_accumulating" to (wakeAttemptStartedAtMs != null)),
                )
            }

            is Insta360SupervisorEvent.ScanWindowOpened -> {
                InstaLog.log(
                    InstaLogCategory.SUP, role = role, event = "scan_window_opened",
                    fields = mapOf("duration_ms" to event.durationMs),
                )
            }

            is Insta360SupervisorEvent.ScanHit -> {
                consecutiveEmptyScanWindows = 0
                mutateHealth {
                    it.copy(rssi = event.rssi, lastSeenAtMs = nowMs())
                }
                InstaLog.log(
                    InstaLogCategory.SUP, role = role, event = "scan_hit",
                    fields = mapOf("rssi" to event.rssi),
                )
                transition(Insta360ConnectionState.CONNECTING, "scan_hit")
            }

            Insta360SupervisorEvent.ScanWindowClosedNoHit -> {
                consecutiveEmptyScanWindows += 1
                InstaLog.log(
                    InstaLogCategory.SUP, role = role, event = "scan_window_closed_no_hit",
                    fields = mapOf("consecutive" to consecutiveEmptyScanWindows),
                )
                checkPersistentClassifier()
            }

            is Insta360SupervisorEvent.WakeCycleStarted -> {
                // Wake events count toward the wake-stall threshold ONLY when
                // the supervisor is actively trying to discover/connect a
                // camera. Filter out RECONNECTING / BLE_READY / WIFI_BOUND /
                // BLE_SUSPENDED / LOST / GIVE_UP states.
                val st = _health.value.state
                if (st != Insta360ConnectionState.SEARCHING && st != Insta360ConnectionState.CONNECTING) {
                    return@withLock
                }
                if (wakeAttemptStartedAtMs == null) {
                    wakeAttemptStartedAtMs = nowMs()
                }
                InstaLog.log(
                    InstaLogCategory.WAKE, role = role, event = "wake_strategy_started",
                    fields = mapOf("strategy" to event.strategy),
                )
                checkWakeStall()
            }

            Insta360SupervisorEvent.ReadinessProbeStarted -> {
                InstaLog.log(InstaLogCategory.SUP, role = role, event = "readiness_probe_started")
            }

            is Insta360SupervisorEvent.ReadinessProbeAck -> {
                mutateHealth { it.copy(lastCommandSuccessAtMs = nowMs()) }
                wakeAttemptStartedAtMs = null
                wakeStallPromptEmitted = false
                InstaLog.log(
                    InstaLogCategory.SUP, role = role, event = "readiness_probe_ack",
                    fields = mapOf("elapsed_ms" to event.elapsedMs),
                )
                transition(Insta360ConnectionState.BLE_READY, "readiness_probe_ack")
            }

            is Insta360SupervisorEvent.ReadinessProbeFailed -> {
                mutateHealth { it.copy(lastError = event.error, lastErrorAtMs = nowMs()) }
                InstaLog.log(
                    InstaLogCategory.SUP, role = role, level = InstaLogLevel.WARN,
                    event = "readiness_probe_failed",
                    fields = mapOf("error" to event.error),
                )
            }

            is Insta360SupervisorEvent.ConnectFailed -> {
                mutateHealth {
                    it.copy(
                        lastError = event.error,
                        lastErrorAtMs = nowMs(),
                        connectAttemptsThisSession = it.connectAttemptsThisSession + 1,
                    )
                }
                InstaLog.log(
                    InstaLogCategory.SUP, role = role, level = InstaLogLevel.WARN,
                    event = "connect_failed",
                    fields = mapOf("error" to event.error),
                )
            }

            is Insta360SupervisorEvent.HeartbeatAck -> {
                mutateHealth {
                    it.copy(
                        consecutiveHeartbeatMisses = 0,
                        rssi = event.rssi ?: it.rssi,
                        lastCommandSuccessAtMs = nowMs(),
                        lastSeenAtMs = nowMs(),
                    )
                }
                InstaLog.log(
                    InstaLogCategory.SUP, role = role, level = InstaLogLevel.DEBUG,
                    event = "heartbeat_ack",
                    fields = mapOf("rssi" to event.rssi),
                )
                if (_health.value.state == Insta360ConnectionState.BLE_DEGRADED) {
                    transition(Insta360ConnectionState.BLE_READY, "heartbeat_recovered")
                }
            }

            Insta360SupervisorEvent.HeartbeatMiss -> {
                mutateHealth { it.copy(consecutiveHeartbeatMisses = it.consecutiveHeartbeatMisses + 1) }
                val misses = _health.value.consecutiveHeartbeatMisses
                InstaLog.log(
                    InstaLogCategory.SUP, role = role, level = InstaLogLevel.WARN,
                    event = "heartbeat_miss",
                    fields = mapOf("consecutive" to misses),
                )
                if (misses >= 2 && _health.value.state == Insta360ConnectionState.BLE_READY) {
                    transition(Insta360ConnectionState.BLE_DEGRADED, "heartbeat_miss")
                }
            }

            is Insta360SupervisorEvent.RssiSample -> {
                mutateHealth { it.copy(rssi = event.rssi) }
                if (event.rssi < -85 && _health.value.state == Insta360ConnectionState.BLE_READY) {
                    transition(Insta360ConnectionState.BLE_DEGRADED, "rssi<-85")
                }
            }

            is Insta360SupervisorEvent.UnsolicitedDisconnect -> {
                val now = nowMs()
                lastUnsolicitedDisconnectAtMs?.let { last ->
                    if (now - last < 500L) {
                        InstaLog.log(
                            InstaLogCategory.SUP, role = role, level = InstaLogLevel.DEBUG,
                            event = "unsolicited_disconnect_debounced",
                            fields = mapOf("since_last_ms" to (now - last)),
                        )
                        return@withLock
                    }
                }
                lastUnsolicitedDisconnectAtMs = now
                mutateHealth {
                    it.copy(
                        lastError = event.error,
                        lastErrorAtMs = now,
                        consecutiveHeartbeatMisses = 0,
                    )
                }
                InstaLog.log(
                    InstaLogCategory.BLE, role = role, level = InstaLogLevel.WARN,
                    event = "didDisconnectWithError",
                    fields = mapOf("error" to event.error),
                )
                if (!config.autoReconnectEnabled) {
                    transition(Insta360ConnectionState.LOST, "auto_reconnect_disabled")
                    return@withLock
                }
                transition(Insta360ConnectionState.RECONNECTING, "unsolicited_disconnect")
                scheduleReconnect()
            }

            Insta360SupervisorEvent.WifiAcquired -> {
                mutateHealth { it.copy(wifiInFlight = true) }
                transition(Insta360ConnectionState.WIFI_BOUND, "radio_gate_acquired")
            }

            Insta360SupervisorEvent.WifiReleased -> {
                mutateHealth { it.copy(wifiInFlight = false) }
                transition(Insta360ConnectionState.BLE_READY, "radio_gate_released")
            }

            is Insta360SupervisorEvent.BackgroundEntered -> {
                InstaLog.log(
                    InstaLogCategory.BG, role = role, event = "did_enter_background",
                    fields = mapOf("recording_active" to event.recordingActive),
                )
                if (!event.recordingActive && config.backgroundBLEEnabled) {
                    transition(Insta360ConnectionState.BLE_SUSPENDED, "background_idle")
                }
            }

            Insta360SupervisorEvent.ForegroundEntered -> {
                InstaLog.log(InstaLogCategory.BG, role = role, event = "will_enter_foreground")
                if (_health.value.state == Insta360ConnectionState.BLE_SUSPENDED) {
                    transition(Insta360ConnectionState.SEARCHING, "foreground_resume")
                    scheduleReconnect()
                }
            }

            Insta360SupervisorEvent.ForceReconnectRequested -> {
                InstaLog.log(InstaLogCategory.SUP, role = role, event = "force_reconnect_requested")
                wakeAttemptStartedAtMs = null
                wakeStallPromptEmitted = false
                consecutiveEmptyScanWindows = 0
                reconnectAttempt = 0
                lastUnsolicitedDisconnectAtMs = null
                transition(Insta360ConnectionState.SEARCHING, "force_reconnect")
                scheduleReconnect()
            }

            is Insta360SupervisorEvent.DockPolled -> {
                mutateHealth {
                    it.copy(dockHint = event.status, dockHintLastUpdatedAtMs = nowMs())
                }
            }

            is Insta360SupervisorEvent.BatteryPolled -> {
                mutateHealth {
                    it.copy(batteryPercent = event.percent, batteryCharging = event.charging)
                }
            }

            Insta360SupervisorEvent.Detached -> {
                reconnectJob?.cancel()
                reconnectJob = null
                transition(Insta360ConnectionState.GIVE_UP, "detached")
            }
        }
    }

    // --- Internals ----------------------------------------------------------

    /** Update health by replacing the StateFlow value. */
    private fun mutateHealth(mutator: (Insta360ConnectionHealth) -> Insta360ConnectionHealth) {
        _health.value = mutator(_health.value)
    }

    /**
     * State transition with logging + observer notification. Resets reconnect
     * bookkeeping when entering a healthy state.
     */
    private suspend fun transition(next: Insta360ConnectionState, reason: String?) {
        val previous = _health.value.state
        if (previous == next) return
        mutateHealth { it.copy(state = next, lastStateChangeAtMs = nowMs()) }
        InstaLog.state(
            InstaLogCategory.SUP, role = role,
            from = previous.wireName, to = next.wireName, reason = reason,
        )
        if (next == Insta360ConnectionState.BLE_READY || next == Insta360ConnectionState.WIFI_BOUND) {
            reconnectAttempt = 0
            mutateHealth { it.copy(reconnectAttempt = 0, nextReconnectInMs = null) }
            lastUnsolicitedDisconnectAtMs = null
        }
        _transitions.tryEmit(
            Insta360TransitionEvent(
                bindingKey = bindingKey,
                role = role,
                from = previous,
                to = next,
                reason = reason,
                health = _health.value,
            )
        )
    }

    private fun checkPersistentClassifier() {
        val msSinceLastAdvert: Long? = _health.value.lastSeenAtMs?.let { lastSeen ->
            val now = nowMs()
            if (now > lastSeen) now - lastSeen else 0L
        }
        if (Insta360ReconnectPolicy.shouldClassifyAsLost(
                consecutiveScanWindowsWithoutAdvert = consecutiveEmptyScanWindows,
                lastErrorDescription = _health.value.lastError,
                msSinceLastAdvertisement = msSinceLastAdvert,
                config = config,
            )
        ) {
            InstaLog.log(
                InstaLogCategory.SUP, role = role, level = InstaLogLevel.WARN,
                event = "persistent_classifier_fired",
                fields = mapOf("consecutive_empty_windows" to consecutiveEmptyScanWindows),
            )
            supervisorScope.launch {
                eventMutex.withLock {
                    transition(Insta360ConnectionState.LOST, "persistent_classifier")
                }
            }
        }
    }

    private fun checkWakeStall() {
        if (!config.wakeUserPromptEnabled) return
        if (wakeStallPromptEmitted) return
        val started = wakeAttemptStartedAtMs ?: return
        val elapsedMs = nowMs() - started
        val thresholdMs = (config.wakeStallThresholdSeconds * 1_000).toLong()
        if (elapsedMs >= thresholdMs) {
            wakeStallPromptEmitted = true
            val suggested = if (_health.value.dockHint == Insta360DockStatus.DOCKED) {
                Insta360WakeStallSuggestedAction.REMOVE_FROM_DOCK
            } else {
                Insta360WakeStallSuggestedAction.POWER_BUTTON
            }
            InstaLog.log(
                InstaLogCategory.WAKE, role = role, level = InstaLogLevel.STATE,
                event = "wake_user_prompt_emitted",
                fields = mapOf(
                    "elapsed_ms" to elapsedMs,
                    "suggested_action" to suggested.wireName,
                ),
            )
            emitWakeStall(suggested)
        }
    }

    private fun emitWakeStall(suggested: Insta360WakeStallSuggestedAction) {
        _wakeStalls.tryEmit(
            Insta360WakeStallEvent(
                bindingKey = bindingKey,
                role = role,
                suggested = suggested,
                health = _health.value,
            )
        )
    }

    private fun scheduleReconnect() {
        val driver = reconnectDriver ?: return
        reconnectJob?.cancel()
        reconnectAttempt += 1
        val attempt = reconnectAttempt
        val delaySeconds = policy.backoffSeconds(attempt)
        val delayMs = (delaySeconds * 1_000).toLong()
        mutateHealth { it.copy(reconnectAttempt = attempt, nextReconnectInMs = delayMs) }
        InstaLog.log(
            InstaLogCategory.SUP, role = role, event = "reconnect_scheduled",
            fields = mapOf("attempt" to attempt, "backoff_ms" to delayMs),
        )
        reconnectJob = supervisorScope.launch {
            delay(delayMs)
            runReconnectAttempt(attempt, driver)
        }
    }

    private suspend fun runReconnectAttempt(attempt: Int, driver: Insta360ReconnectDriver) {
        InstaLog.log(
            InstaLogCategory.SUP, role = role, event = "reconnect_attempt",
            fields = mapOf("attempt" to attempt),
        )
        try {
            driver()
            InstaLog.log(
                InstaLogCategory.SUP, role = role, event = "reconnect_success",
                fields = mapOf("attempt" to attempt),
            )
            // Successful reconnect signals will arrive via subsequent events
            // (scanHit → readinessProbeAck → bleReady). No state change here.
        } catch (e: kotlinx.coroutines.CancellationException) {
            InstaLog.log(
                InstaLogCategory.SUP, role = role, level = InstaLogLevel.DEBUG,
                event = "reconnect_superseded",
                fields = mapOf("attempt" to attempt),
            )
            throw e
        } catch (t: Throwable) {
            val description = t.message ?: t::class.java.simpleName
            eventMutex.withLock {
                mutateHealth {
                    it.copy(
                        lastError = description,
                        lastErrorAtMs = nowMs(),
                        connectAttemptsThisSession = it.connectAttemptsThisSession + 1,
                    )
                }
            }
            InstaLog.log(
                InstaLogCategory.SUP, role = role, level = InstaLogLevel.WARN,
                event = "reconnect_failed",
                fields = mapOf("attempt" to attempt, "error" to description),
            )
            if (description.contains("user_action_required")) {
                eventMutex.withLock {
                    if (!_health.value.state.isTerminal) {
                        transition(Insta360ConnectionState.LOST, "reconnect_user_action_required")
                    }
                }
                return
            }
            eventMutex.withLock { checkPersistentClassifier() }
            if (attempt < policy.maxAttempts && !_health.value.state.isTerminal) {
                eventMutex.withLock { scheduleReconnect() }
            }
        }
    }

    private fun nowMs(): Long = clock()
}
