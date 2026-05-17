package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class Insta360RadioGateTest {

    @Before
    fun setUp() {
        Insta360CoordinatorConfig.resetForTest()
    }

    @After
    fun tearDown() {
        Insta360CoordinatorConfig.resetForTest()
    }

    /** Records all setHeartbeatIntervalMs calls as `bindingKey -> [intervals]`. */
    private class HeartbeatRecorder {
        val log = ConcurrentHashMap<String, MutableList<Long?>>()
        val sink: suspend (String, Long?) -> Unit = { k, ms ->
            log.getOrPut(k) { mutableListOf() }.add(ms)
        }
        fun intervalsFor(key: String): List<Long?> = log[key]?.toList() ?: emptyList()
    }

    @Test
    fun withWiFi_setsHolderPausedAndOthersSlowMode() = runTest {
        val recorder = HeartbeatRecorder()
        val gate = Insta360RadioGate(setHeartbeatIntervalMs = recorder.sink)
        gate.register("A")
        gate.register("B")
        gate.register("C")

        gate.withWiFi(bindingKey = "A") { lease ->
            assertThat(lease.bindingKey).isEqualTo("A")
            // After acquire: A paused (null), B and C demoted to 8000
            assertThat(recorder.intervalsFor("A")).contains(null)
            assertThat(recorder.intervalsFor("B")).contains(8_000L)
            assertThat(recorder.intervalsFor("C")).contains(8_000L)
        }

        // After release: all restored to default 2000
        val aLast = recorder.intervalsFor("A").last()
        val bLast = recorder.intervalsFor("B").last()
        assertThat(aLast).isEqualTo(2_000L)
        assertThat(bLast).isEqualTo(2_000L)
    }

    @Test
    fun withWiFi_serializes_secondCallerWaitsForFirstToRelease() = runTest {
        val recorder = HeartbeatRecorder()
        val gate = Insta360RadioGate(setHeartbeatIntervalMs = recorder.sink)
        gate.register("A")
        gate.register("B")

        val order = mutableListOf<String>()

        val first = launch {
            gate.withWiFi(bindingKey = "A") {
                order.add("A_enter")
                delay(1_000)
                order.add("A_exit")
            }
        }
        // Give first task time to acquire
        advanceTimeBy(100)
        val second = launch {
            gate.withWiFi(bindingKey = "B") {
                order.add("B_enter")
            }
        }
        advanceUntilIdle()
        first.join()
        second.join()

        assertThat(order).containsExactly("A_enter", "A_exit", "B_enter").inOrder()
    }

    @Test
    fun withWiFi_currentHolderReflectsLease() = runTest {
        val recorder = HeartbeatRecorder()
        val gate = Insta360RadioGate(setHeartbeatIntervalMs = recorder.sink)
        gate.register("A")
        assertThat(gate.currentLeaseHolder()).isNull()
        val holderDuring = AtomicReference<String?>()
        gate.withWiFi(bindingKey = "A") {
            holderDuring.set(gate.currentLeaseHolder())
        }
        assertThat(holderDuring.get()).isEqualTo("A")
        assertThat(gate.currentLeaseHolder()).isNull()
    }

    @Test
    fun withWiFi_cleansUpOnException() = runTest {
        val recorder = HeartbeatRecorder()
        val gate = Insta360RadioGate(setHeartbeatIntervalMs = recorder.sink)
        gate.register("A")
        gate.register("B")
        try {
            gate.withWiFi("A") {
                throw IllegalStateException("download failed")
            }
        } catch (_: IllegalStateException) { /* expected */ }
        // Cleanup ran: both keys see restoration
        assertThat(recorder.intervalsFor("A").last()).isEqualTo(2_000L)
        assertThat(recorder.intervalsFor("B").last()).isEqualTo(2_000L)
        assertThat(gate.currentLeaseHolder()).isNull()
    }

    @Test
    fun withWiFi_returnsBodyResult() = runTest {
        val gate = Insta360RadioGate { _, _ -> }
        gate.register("A")
        val result = gate.withWiFi("A") { 42 }
        assertThat(result).isEqualTo(42)
    }

    @Test
    fun unregister_excludesFromSlowMode() = runTest {
        val recorder = HeartbeatRecorder()
        val gate = Insta360RadioGate(setHeartbeatIntervalMs = recorder.sink)
        gate.register("A")
        gate.register("B")
        gate.unregister("B")
        gate.withWiFi("A") { /* no-op */ }
        // B was unregistered, should NOT have been demoted
        assertThat(recorder.intervalsFor("B")).isEmpty()
    }

    @Test
    fun customSlowInterval_appliesFromConfig() = runTest {
        Insta360CoordinatorConfig.radioGateSlowHeartbeatIntervalMs = 4_000L
        Insta360CoordinatorConfig.heartbeatIntervalMs = 1_000L
        val recorder = HeartbeatRecorder()
        val gate = Insta360RadioGate(setHeartbeatIntervalMs = recorder.sink)
        gate.register("A")
        gate.register("B")
        gate.withWiFi("A") {
            assertThat(recorder.intervalsFor("B")).contains(4_000L)
        }
        // Restore uses heartbeatIntervalMs (1000), not the slow value
        assertThat(recorder.intervalsFor("B").last()).isEqualTo(1_000L)
        assertThat(recorder.intervalsFor("A").last()).isEqualTo(1_000L)
    }
}
