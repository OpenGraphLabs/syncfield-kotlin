package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Insta360BackgroundSupervisorTest {

    @Before
    fun setUp() {
        Insta360ConnectionCoordinator.resetForTest()
        Insta360CoordinatorConfig.resetForTest()
    }

    @After
    fun tearDown() {
        Insta360ConnectionCoordinator.resetForTest()
        Insta360CoordinatorConfig.resetForTest()
    }

    @Test
    fun handleBackground_fansBackgroundEnteredToAllAttachedSupervisors() = kotlinx.coroutines.runBlocking {
        val left = Insta360ConnectionCoordinator.attach("B_left", role = "left")
        val right = Insta360ConnectionCoordinator.attach("B_right", role = "right")
        // Drive both to BLE_READY first so background can transition to BLE_SUSPENDED
        Insta360ConnectionCoordinator.feed("B_left", Insta360SupervisorEvent.ScanHit(-50))
        Insta360ConnectionCoordinator.feed("B_left", Insta360SupervisorEvent.ReadinessProbeAck(50))
        Insta360ConnectionCoordinator.feed("B_right", Insta360SupervisorEvent.ScanHit(-50))
        Insta360ConnectionCoordinator.feed("B_right", Insta360SupervisorEvent.ReadinessProbeAck(50))
        kotlinx.coroutines.delay(50)

        val supervisor = Insta360BackgroundSupervisor(recordingActiveProvider = { false })
        supervisor.handleBackground()
        kotlinx.coroutines.delay(100)

        assertThat(left.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_SUSPENDED)
        assertThat(right.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_SUSPENDED)
    }

    @Test
    fun handleBackground_whileRecording_stayBleReady() = kotlinx.coroutines.runBlocking {
        val left = Insta360ConnectionCoordinator.attach("B_left", role = "left")
        Insta360ConnectionCoordinator.feed("B_left", Insta360SupervisorEvent.ScanHit(-50))
        Insta360ConnectionCoordinator.feed("B_left", Insta360SupervisorEvent.ReadinessProbeAck(50))
        kotlinx.coroutines.delay(50)

        val supervisor = Insta360BackgroundSupervisor(recordingActiveProvider = { true })
        supervisor.handleBackground()
        kotlinx.coroutines.delay(100)

        assertThat(left.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_READY)
    }

    @Test
    fun handleForeground_resumesFromBleSuspended() = kotlinx.coroutines.runBlocking {
        val left = Insta360ConnectionCoordinator.attach("B_left", role = "left")
        Insta360ConnectionCoordinator.feed("B_left", Insta360SupervisorEvent.ScanHit(-50))
        Insta360ConnectionCoordinator.feed("B_left", Insta360SupervisorEvent.ReadinessProbeAck(50))
        Insta360ConnectionCoordinator.feed("B_left", Insta360SupervisorEvent.BackgroundEntered(false))
        kotlinx.coroutines.delay(50)
        assertThat(left.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_SUSPENDED)

        val supervisor = Insta360BackgroundSupervisor(recordingActiveProvider = { false })
        supervisor.handleForeground()
        kotlinx.coroutines.delay(100)
        assertThat(left.snapshot().state).isEqualTo(Insta360ConnectionState.SEARCHING)
    }

    @Test
    fun backgroundBLEDisabledConfig_skipsBackgroundFanOut() = kotlinx.coroutines.runBlocking {
        Insta360CoordinatorConfig.backgroundBLEEnabled = false
        val left = Insta360ConnectionCoordinator.attach("B_left", role = "left")
        Insta360ConnectionCoordinator.feed("B_left", Insta360SupervisorEvent.ScanHit(-50))
        Insta360ConnectionCoordinator.feed("B_left", Insta360SupervisorEvent.ReadinessProbeAck(50))
        kotlinx.coroutines.delay(50)

        val supervisor = Insta360BackgroundSupervisor(recordingActiveProvider = { false })
        supervisor.handleBackground()
        kotlinx.coroutines.delay(100)

        // Config disabled → no transition
        assertThat(left.snapshot().state).isEqualTo(Insta360ConnectionState.BLE_READY)
    }
}
