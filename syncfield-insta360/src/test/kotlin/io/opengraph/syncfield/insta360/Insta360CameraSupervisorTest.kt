package io.opengraph.syncfield.insta360

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class Insta360CameraSupervisorTest {

    @Before
    fun setUp() {
        Insta360CoordinatorConfig.resetForTest()
    }

    @After
    fun tearDown() {
        Insta360CoordinatorConfig.resetForTest()
    }

    private fun supervisor(
        scope: TestScope,
        reconnectDriver: Insta360ReconnectDriver? = null,
        clockMs: () -> Long = { 0L },
    ): Insta360CameraSupervisor = Insta360CameraSupervisor(
        bindingKey = "B1",
        role = "left",
        parentScope = scope,
        reconnectDriver = reconnectDriver,
        clock = clockMs,
    )

    @Test
    fun initialState_isIdle() = runTest {
        val s = supervisor(this)
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.IDLE)
    }

    @Test
    fun attached_transitionsToSearching() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.SEARCHING)
    }

    @Test
    fun scanHit_transitionsToConnecting() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(rssi = -42))
        val h = s.snapshot()
        assertThat(h.state).isEqualTo(Insta360ConnectionState.CONNECTING)
        assertThat(h.rssi).isEqualTo(-42)
    }

    @Test
    fun readinessProbeAck_transitionsToBleReady() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(rssi = -42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(elapsedMs = 120L))
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_READY)
    }

    @Test
    fun fullHappyPath_emitsExactTransitionSequence() = runTest {
        val s = supervisor(this)
        s.transitions.test {
            s.handle(Insta360SupervisorEvent.Attached)
            assertThat(awaitItem().to).isEqualTo(Insta360ConnectionState.SEARCHING)
            s.handle(Insta360SupervisorEvent.ScanHit(rssi = -42))
            assertThat(awaitItem().to).isEqualTo(Insta360ConnectionState.CONNECTING)
            s.handle(Insta360SupervisorEvent.ReadinessProbeAck(elapsedMs = 50))
            assertThat(awaitItem().to).isEqualTo(Insta360ConnectionState.BLE_READY)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun heartbeatMiss_2InARow_degradesFromBleReady() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(rssi = -42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(elapsedMs = 50))
        s.handle(Insta360SupervisorEvent.HeartbeatMiss)
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_READY)
        s.handle(Insta360SupervisorEvent.HeartbeatMiss)
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_DEGRADED)
        assertThat(s.snapshot().consecutiveHeartbeatMisses).isEqualTo(2)
    }

    @Test
    fun heartbeatAck_recoversFromDegraded() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(rssi = null))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(elapsedMs = 50))
        s.handle(Insta360SupervisorEvent.HeartbeatMiss)
        s.handle(Insta360SupervisorEvent.HeartbeatMiss)
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_DEGRADED)
        s.handle(Insta360SupervisorEvent.HeartbeatAck(rssi = -60))
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_READY)
        assertThat(s.snapshot().consecutiveHeartbeatMisses).isEqualTo(0)
    }

    @Test
    fun rssiBelowMinus85_degradesFromBleReady() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(rssi = -50))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(elapsedMs = 50))
        s.handle(Insta360SupervisorEvent.RssiSample(-86))
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_DEGRADED)
    }

    @Test
    fun unsolicitedDisconnect_transitionsToReconnectingAndSchedulesRetry() = runTest {
        var driverCalls = 0
        var t = 0L
        val s = supervisor(this, reconnectDriver = { driverCalls += 1 }, clockMs = { t })

        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(rssi = -42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(50))
        s.handle(Insta360SupervisorEvent.UnsolicitedDisconnect(error = "ble_drop"))

        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.RECONNECTING)
        assertThat(s.snapshot().reconnectAttempt).isEqualTo(1)
        assertThat(s.snapshot().nextReconnectInMs).isEqualTo(500L)

        // Drive virtual time past the first backoff (500ms)
        advanceTimeBy(600)
        runCurrent()
        assertThat(driverCalls).isEqualTo(1)
    }

    @Test
    fun duplicateUnsolicitedDisconnect_within500ms_isDebounced() = runTest {
        var driverCalls = 0
        var t = 0L
        val s = supervisor(this, reconnectDriver = { driverCalls += 1 }, clockMs = { t })

        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(rssi = -42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(50))

        t = 1000L
        s.handle(Insta360SupervisorEvent.UnsolicitedDisconnect(error = "first"))
        assertThat(s.snapshot().reconnectAttempt).isEqualTo(1)

        // Second disconnect 200ms later — should debounce
        t = 1200L
        s.handle(Insta360SupervisorEvent.UnsolicitedDisconnect(error = "duplicate"))
        assertThat(s.snapshot().reconnectAttempt).isEqualTo(1) // unchanged

        // Third disconnect 600ms later — should advance attempt
        t = 1800L
        s.handle(Insta360SupervisorEvent.UnsolicitedDisconnect(error = "real_drop"))
        assertThat(s.snapshot().reconnectAttempt).isEqualTo(2)
    }

    @Test
    fun forceReconnect_resetsAllCountersAndScansFresh() = runTest {
        var driverCalls = 0
        var t = 0L
        val s = supervisor(this, reconnectDriver = { driverCalls += 1 }, clockMs = { t })

        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(-42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(50))

        // Trigger 3 disconnects to build up reconnect ramp (1s gap between them
        // to avoid debounce)
        for (i in 1..3) {
            t += 1000L
            s.handle(Insta360SupervisorEvent.UnsolicitedDisconnect(error = "drop$i"))
        }
        assertThat(s.snapshot().reconnectAttempt).isEqualTo(3)

        // ForceReconnect resets to attempt 0 → scheduleReconnect produces attempt=1 backoff 500ms
        s.handle(Insta360SupervisorEvent.ForceReconnectRequested)
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.SEARCHING)
        assertThat(s.snapshot().reconnectAttempt).isEqualTo(1)
        assertThat(s.snapshot().nextReconnectInMs).isEqualTo(500L)
    }

    @Test
    fun wifiAcquired_transitionsToWifiBound() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(-42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(50))
        s.handle(Insta360SupervisorEvent.WifiAcquired)
        val h = s.snapshot()
        assertThat(h.state).isEqualTo(Insta360ConnectionState.WIFI_BOUND)
        assertThat(h.wifiInFlight).isTrue()
    }

    @Test
    fun wifiReleased_returnsToBleReady() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(-42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(50))
        s.handle(Insta360SupervisorEvent.WifiAcquired)
        s.handle(Insta360SupervisorEvent.WifiReleased)
        val h = s.snapshot()
        assertThat(h.state).isEqualTo(Insta360ConnectionState.BLE_READY)
        assertThat(h.wifiInFlight).isFalse()
    }

    @Test
    fun backgroundEntered_idle_transitionsToBleSuspended() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(-42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(50))
        s.handle(Insta360SupervisorEvent.BackgroundEntered(recordingActive = false))
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_SUSPENDED)
    }

    @Test
    fun backgroundEntered_whileRecording_stayBleReady() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(-42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(50))
        s.handle(Insta360SupervisorEvent.BackgroundEntered(recordingActive = true))
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_READY)
    }

    @Test
    fun foregroundEntered_fromSuspended_transitionsToSearching() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(-42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(50))
        s.handle(Insta360SupervisorEvent.BackgroundEntered(recordingActive = false))
        s.handle(Insta360SupervisorEvent.ForegroundEntered)
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.SEARCHING)
    }

    @Test
    fun emptyScanWindows_3InARow_classifiesAsLost() = runTest {
        // wake stall not relevant here — disable to keep test focused
        Insta360CoordinatorConfig.wakeUserPromptEnabled = false
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanWindowClosedNoHit)
        s.handle(Insta360SupervisorEvent.ScanWindowClosedNoHit)
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.SEARCHING)
        s.handle(Insta360SupervisorEvent.ScanWindowClosedNoHit)
        advanceUntilIdle()
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.LOST)
    }

    @Test
    fun wakeStall_after12s_emitsPowerButtonSuggestion() = runTest {
        var t = 0L
        val s = supervisor(this, clockMs = { t })
        s.wakeStalls.test {
            s.handle(Insta360SupervisorEvent.Attached)
            t = 1000L
            s.handle(Insta360SupervisorEvent.WakeCycleStarted(strategy = "fast_scan"))
            // Not yet — only 0 ms elapsed since wake started
            expectNoEvents()
            t = 14_000L
            s.handle(Insta360SupervisorEvent.WakeCycleStarted(strategy = "targeted"))
            val emission = awaitItem()
            assertThat(emission.suggested).isEqualTo(Insta360WakeStallSuggestedAction.POWER_BUTTON)
            assertThat(emission.bindingKey).isEqualTo("B1")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun wakeStall_whenDocked_emitsRemoveFromDockSuggestion() = runTest {
        var t = 0L
        val s = supervisor(this, clockMs = { t })
        s.handle(Insta360SupervisorEvent.DockPolled(Insta360DockStatus.DOCKED))
        s.wakeStalls.test {
            s.handle(Insta360SupervisorEvent.Attached)
            t = 1000L
            s.handle(Insta360SupervisorEvent.WakeCycleStarted("fast_scan"))
            t = 15_000L
            s.handle(Insta360SupervisorEvent.WakeCycleStarted("targeted"))
            val emission = awaitItem()
            assertThat(emission.suggested).isEqualTo(Insta360WakeStallSuggestedAction.REMOVE_FROM_DOCK)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun wakeStall_outsideSearchingOrConnecting_doesNotFire() = runTest {
        var t = 0L
        val s = supervisor(this, clockMs = { t })
        // Get to BLE_READY first
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(-42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(50))
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_READY)
        s.wakeStalls.test {
            // Wake cycle while bleReady is automatic recovery — must NOT prompt user
            t = 100_000L
            s.handle(Insta360SupervisorEvent.WakeCycleStarted("targeted"))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun recordingReadinessFailed_emitsWakeStallAndTransitionsToLost() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(-42))
        // Camera is responsive but recording probe fails
        s.wakeStalls.test {
            s.handle(Insta360SupervisorEvent.RecordingReadinessFailed)
            val emission = awaitItem()
            assertThat(emission.suggested).isEqualTo(Insta360WakeStallSuggestedAction.POWER_BUTTON)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.LOST)
    }

    @Test
    fun pairAttemptStarted_fromLost_returnsToSearching() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.RecordingReadinessFailed)
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.LOST)
        s.handle(Insta360SupervisorEvent.PairAttemptStarted)
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.SEARCHING)
    }

    @Test
    fun detached_transitionsToGiveUp_terminal() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(-42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(50))
        s.handle(Insta360SupervisorEvent.Detached)
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.GIVE_UP)
        assertThat(s.snapshot().state.isTerminal).isTrue()
    }

    @Test
    fun pairAttemptStarted_fromGiveUp_doesNotRecover() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.Detached)
        s.handle(Insta360SupervisorEvent.PairAttemptStarted)
        // giveUp is permanent — pairAttempt does NOT bring us back
        assertThat(s.snapshot().state).isEqualTo(Insta360ConnectionState.GIVE_UP)
    }

    @Test
    fun batteryPolled_updatesHealth() = runTest {
        val s = supervisor(this)
        s.handle(Insta360SupervisorEvent.BatteryPolled(percent = 67, charging = true))
        val h = s.snapshot()
        assertThat(h.batteryPercent).isEqualTo(67)
        assertThat(h.batteryCharging).isTrue()
    }

    @Test
    fun bleReadyTransition_resetsReconnectBookkeeping() = runTest {
        var t = 0L
        val s = supervisor(this, reconnectDriver = { /* no-op */ }, clockMs = { t })
        s.handle(Insta360SupervisorEvent.Attached)
        s.handle(Insta360SupervisorEvent.ScanHit(-42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(50))
        t = 1000L
        s.handle(Insta360SupervisorEvent.UnsolicitedDisconnect("drop"))
        assertThat(s.snapshot().reconnectAttempt).isEqualTo(1)
        // Simulate the reconnect driver succeeding → fresh scan path
        s.handle(Insta360SupervisorEvent.ScanHit(-42))
        s.handle(Insta360SupervisorEvent.ReadinessProbeAck(80))
        val h = s.snapshot()
        assertThat(h.state).isEqualTo(Insta360ConnectionState.BLE_READY)
        assertThat(h.reconnectAttempt).isEqualTo(0)  // reset
        assertThat(h.nextReconnectInMs).isNull()
    }
}
