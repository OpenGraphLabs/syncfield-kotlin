package io.opengraph.syncfield.streams

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure-JVM unit coverage for [FrameProcessorGate]. The gate is the
 * piece responsible for keeping the camera analyzer thread unblocked
 * — this is the contract that turns the iOS/Android FPS-drop bug into
 * a non-issue, so the regression net needs teeth in a tier that
 * doesn't require a device.
 *
 * Mirrors the iOS `FrameProcessorGateTests` suite in `syncfield-swift`.
 */
class FrameProcessorGateTest {

    @Test
    fun `first enqueue dispatches work`() {
        val gate = FrameProcessorGate("test.first")
        val ran = CountDownLatch(1)
        val scheduled = gate.tryEnqueue { ran.countDown() }
        assertThat(scheduled).isTrue()
        assertThat(ran.await(1, TimeUnit.SECONDS)).isTrue()
        gate.shutdown()
    }

    @Test
    fun `concurrent enqueue is dropped while busy`() {
        val gate = FrameProcessorGate("test.busy")
        val firstStarted = CountDownLatch(1)
        val firstMayFinish = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)

        val firstScheduled = gate.tryEnqueue {
            firstStarted.countDown()
            // Hold the gate busy until the test releases us.
            firstMayFinish.await(2, TimeUnit.SECONDS)
            firstFinished.countDown()
        }
        assertThat(firstScheduled).isTrue()

        // Wait until the first task is actually executing.
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue()

        // A second tryEnqueue while busy must be rejected. Use a
        // counter so we can also verify it never executes.
        val secondRunCount = AtomicInteger(0)
        val secondScheduled = gate.tryEnqueue { secondRunCount.incrementAndGet() }
        assertThat(secondScheduled).isFalse()
        assertThat(secondRunCount.get()).isEqualTo(0)

        firstMayFinish.countDown()
        assertThat(firstFinished.await(1, TimeUnit.SECONDS)).isTrue()

        gate.shutdown()
    }

    @Test
    fun `enqueue succeeds again after previous finishes`() {
        val gate = FrameProcessorGate("test.again")
        val firstDone = CountDownLatch(1)
        assertThat(gate.tryEnqueue { firstDone.countDown() }).isTrue()
        assertThat(firstDone.await(1, TimeUnit.SECONDS)).isTrue()

        // Spin briefly while the busy flag flips back. The reset
        // happens after the closure returns but on the executor
        // thread, so a microscopic window can race the next caller.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
        while (gate.isBusy && System.nanoTime() < deadline) {
            Thread.sleep(2)
        }
        assertThat(gate.isBusy).isFalse()

        val secondDone = CountDownLatch(1)
        assertThat(gate.tryEnqueue { secondDone.countDown() }).isTrue()
        assertThat(secondDone.await(1, TimeUnit.SECONDS)).isTrue()
        gate.shutdown()
    }

    @Test
    fun `drain blocks until in-flight work finishes`() {
        val gate = FrameProcessorGate("test.drain")
        val workFinished = CountDownLatch(1)

        val started = System.nanoTime()
        assertThat(gate.tryEnqueue {
            Thread.sleep(150)
            workFinished.countDown()
        }).isTrue()

        gate.drain()

        // drain returned: the closure must have completed already.
        assertThat(workFinished.count).isEqualTo(0L)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertThat(elapsedMs).isAtLeast(100L)
        gate.shutdown()
    }

    @Test
    fun `drain on idle returns immediately`() {
        val gate = FrameProcessorGate("test.drain-idle")
        val started = System.nanoTime()
        gate.drain()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertThat(elapsedMs).isLessThan(50L)
        gate.shutdown()
    }

    /**
     * Sustained throughput under drop-on-busy: with a 50 ms task and a
     * caller offering work every 10 ms for 500 ms, the gate accepts
     * roughly `500 / 50 = 10` units (± scheduler slack) and drops the
     * rest. Validates that drop-on-busy actually serializes execution
     * to one detector pass at a time — the contract that lets
     * MediaPipe `.video` mode (and any other tracker with cross-frame
     * state) run unmodified inside the closure.
     */
    @Test
    fun `drop on busy serializes under load`() {
        val gate = FrameProcessorGate("test.load")
        val runCount = AtomicInteger(0)
        val offerEndNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
        var accepted = 0

        while (System.nanoTime() < offerEndNs) {
            val scheduled = gate.tryEnqueue {
                Thread.sleep(50)
                runCount.incrementAndGet()
            }
            if (scheduled) accepted++
            Thread.sleep(10)
        }
        gate.drain()

        assertWithMessage("every accepted work item must have run exactly once")
            .that(runCount.get())
            .isEqualTo(accepted)
        assertWithMessage("expected ~10 accepted in 500ms with 50ms work")
            .that(accepted)
            .isAtLeast(7)
        assertWithMessage("drop-on-busy must cap accepted units near work-duration ceiling")
            .that(accepted)
            .isAtMost(12)
        gate.shutdown()
    }

    @Test
    fun `shutdown is idempotent`() {
        val gate = FrameProcessorGate("test.shutdown")
        gate.shutdown()
        gate.shutdown()  // second call must not throw
    }

    @Test
    fun `drain after shutdown returns immediately without throwing`() {
        val gate = FrameProcessorGate("test.drain-after-shutdown")
        gate.shutdown()
        val started = System.nanoTime()
        gate.drain()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertThat(elapsedMs).isLessThan(50L)
    }
}
