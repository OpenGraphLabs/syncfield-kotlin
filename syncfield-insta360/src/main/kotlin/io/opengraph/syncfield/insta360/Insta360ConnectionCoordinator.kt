package io.opengraph.syncfield.insta360

import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide owner of all per-camera supervisors + radio gate.
 *
 * Hosts attach a binding key once per paired camera; the coordinator:
 *  - creates a supervisor with the right reconnect driver
 *  - fans the supervisor's [Insta360TransitionEvent] into a process-wide
 *    [stateEvents] flow (RN bridge subscribes once and forwards to JS)
 *  - merges [Insta360WakeStallEvent] flows into a single [wakeStallEvents]
 *  - exposes [withWiFi] that delegates to [Insta360RadioGate], with the
 *    heartbeat router wired to the actual `BLEController` instances
 *
 * Singleton pattern matches Swift `Insta360ConnectionCoordinator.shared`.
 *
 * For test isolation, [resetForTest] cancels supervisors and clears state.
 */
object Insta360ConnectionCoordinator {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )
    private val mutex = Mutex()
    private val supervisors = mutableMapOf<String, Insta360CameraSupervisor>()
    private val forwarderJobs = mutableMapOf<String, Job>()

    /**
     * Resolver for `bindingKey → BLEController`. Wired by the bridge so the
     * coordinator's [Insta360RadioGate] can drive `setHeartbeatIntervalMs`
     * on the live controller for each binding key. Returning null means
     * the controller isn't available (e.g. supervisor attached before the
     * camera was actually paired) — RadioGate then silently no-ops.
     */
    @Volatile
    var bleControllerResolver: ((bindingKey: String) -> Insta360BLEController?)? = null

    val radioGate: Insta360RadioGate = Insta360RadioGate { bindingKey, ms ->
        bleControllerResolver?.invoke(bindingKey)?.setHeartbeatIntervalMs(ms)
    }

    private val _stateEvents = MutableSharedFlow<Insta360TransitionEvent>(
        replay = 0,
        extraBufferCapacity = 128,
    )
    val stateEvents: SharedFlow<Insta360TransitionEvent> = _stateEvents.asSharedFlow()

    private val _wakeStallEvents = MutableSharedFlow<Insta360WakeStallEvent>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    val wakeStallEvents: SharedFlow<Insta360WakeStallEvent> = _wakeStallEvents.asSharedFlow()

    /**
     * Attach a supervisor for [bindingKey]. Idempotent — returns the existing
     * supervisor if one is already attached.
     *
     * Sends [Insta360SupervisorEvent.Attached] to start the state machine
     * and registers with [radioGate].
     */
    suspend fun attach(
        bindingKey: String,
        role: String?,
        reconnectDriver: Insta360ReconnectDriver? = null,
    ): Insta360CameraSupervisor = mutex.withLock {
        supervisors[bindingKey]?.let { return@withLock it }
        val supervisor = Insta360CameraSupervisor(
            bindingKey = bindingKey,
            role = role,
            parentScope = scope,
            reconnectDriver = reconnectDriver,
        )
        supervisors[bindingKey] = supervisor
        radioGate.register(bindingKey)
        // Fan supervisor transitions/wake-stalls into process-wide flows.
        // UNDISPATCHED start means the collect() runs synchronously in the
        // caller's thread until it suspends — by the time attach() returns
        // and calls supervisor.handle(Attached), the subscription is in
        // place and the resulting transition is captured.
        forwarderJobs[bindingKey] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                supervisor.transitions.collect { _stateEvents.tryEmit(it) }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                supervisor.wakeStalls.collect { _wakeStallEvents.tryEmit(it) }
            }
        }
        InstaLog.log(
            InstaLogCategory.COORD, event = "attached",
            fields = mapOf("bindingKey" to bindingKey, "role" to role),
        )
        supervisor.handle(Insta360SupervisorEvent.Attached)
        supervisor
    }

    /** Send [Insta360SupervisorEvent.Detached] and remove from registry. */
    suspend fun detach(bindingKey: String) {
        val supervisor = mutex.withLock {
            forwarderJobs.remove(bindingKey)?.cancel()
            supervisors.remove(bindingKey)
        } ?: return
        supervisor.handle(Insta360SupervisorEvent.Detached)
        radioGate.unregister(bindingKey)
        InstaLog.log(
            InstaLogCategory.COORD, event = "detached",
            fields = mapOf("bindingKey" to bindingKey),
        )
    }

    /** Detach every supervised camera. */
    suspend fun detachAll() {
        val keys = mutex.withLock { supervisors.keys.toList() }
        keys.forEach { detach(it) }
    }

    /** Feed an event to the supervisor for [bindingKey]. No-op if not attached. */
    suspend fun feed(bindingKey: String, event: Insta360SupervisorEvent) {
        val supervisor = mutex.withLock { supervisors[bindingKey] } ?: return
        supervisor.handle(event)
    }

    /** Convenience wrapper around [Insta360SupervisorEvent.ForceReconnectRequested]. */
    suspend fun forceReconnect(bindingKey: String) {
        feed(bindingKey, Insta360SupervisorEvent.ForceReconnectRequested)
    }

    /** Snapshot of a single binding key's health. */
    suspend fun health(bindingKey: String): Insta360ConnectionHealth? =
        mutex.withLock { supervisors[bindingKey] }?.snapshot()

    /** Snapshot of every attached supervisor, keyed by binding key. */
    suspend fun allHealth(): Map<String, Insta360ConnectionHealth> {
        val snap = mutex.withLock { supervisors.toMap() }
        return snap.mapValues { it.value.snapshot() }
    }

    /** Delegate to [Insta360RadioGate.withWiFi]. */
    suspend fun <T> withWiFi(bindingKey: String, body: suspend (Insta360RadioGate.Lease) -> T): T =
        radioGate.withWiFi(bindingKey, body)

    // --- Testing ------------------------------------------------------------

    /**
     * Test-only: cancel all supervisors + forwarder jobs and clear state.
     * Singletons are notoriously leaky between unit tests; call this in
     * `@Before`/`@After` of any test that attaches supervisors.
     */
    internal fun resetForTest() {
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                forwarderJobs.values.forEach { it.cancel() }
                forwarderJobs.clear()
                supervisors.clear()
            }
        }
        bleControllerResolver = null
    }
}
