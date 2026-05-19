package io.opengraph.syncfield.streams

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serial off-thread dispatch with drop-on-busy semantics. The Android
 * camera stream's `ImageAnalysis` analyzer uses this to invoke a
 * host-supplied frame processor without blocking the analyzer thread.
 * When the previous unit of work is still running, new [tryEnqueue]
 * calls return `false`. The caller is expected to drop the frame
 * rather than queue it, matching CameraX's `STRATEGY_KEEP_ONLY_LATEST`
 * policy on the producer side.
 *
 * Without this gate, a host that ran a heavy detector inline on the
 * analyzer thread (MediaPipe hand landmarker, ML Kit, custom Vision
 * pipelines) would block the analyzer for the duration of detection.
 * `STRATEGY_KEEP_ONLY_LATEST` then drops every camera frame that
 * arrives during that block — production iPhone captures showed ego
 * mp4 collapsing to 8–20 fps under exactly this pattern. The same
 * pipeline shape exists on Android.
 *
 * The internal executor is single-threaded. Hosts wrapping a stateful
 * detector (MediaPipe `.video` mode, any tracker with cross-frame
 * state) are guaranteed serial invocation by construction; no extra
 * synchronization required on their side.
 *
 * Mirror of `FrameProcessorGate` in `syncfield-swift` 0.10.0.
 */
internal class FrameProcessorGate(
    threadName: String = "syncfield-camera-processor"
) {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, threadName).apply { isDaemon = true }
        }

    private val busy = AtomicBoolean(false)

    /**
     * Dispatch [work] to the internal serial queue if no prior unit is
     * in flight. Returns `true` when the work was scheduled, `false`
     * when the gate was busy and the work was *not* scheduled. The
     * caller's expected response on `false` is to drop the frame, not
     * to retry — that mirrors the upstream `KEEP_ONLY_LATEST` contract
     * and keeps the analyzer thread free to receive the next sample.
     */
    fun tryEnqueue(work: () -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        executor.execute {
            try {
                work()
            } finally {
                busy.set(false)
            }
        }
        return true
    }

    /**
     * Block the caller until any in-flight work on the internal queue
     * has completed. Hosts use this during teardown (`stopRecording`,
     * `disconnect`) so the last processor callback returns before the
     * surrounding stream releases state it might reference. Relies on
     * single-thread executor ordering: a task submitted after the
     * in-flight one runs *after* it completes.
     *
     * Does nothing useful (returns immediately after a single hop) if
     * the gate is already idle.
     */
    fun drain() {
        val latch = CountDownLatch(1)
        try {
            executor.execute { latch.countDown() }
            latch.await()
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor was already shut down — nothing is in flight,
            // nothing to wait for.
        }
    }

    /** Diagnostic only; [tryEnqueue]'s return value is authoritative. */
    val isBusy: Boolean
        get() = busy.get()

    /**
     * Release the executor thread. Call once when the owning stream is
     * destroyed. Idempotent. Subsequent [tryEnqueue] calls will return
     * `false` because [executor.execute] throws and we revert [busy].
     */
    fun shutdown() {
        executor.shutdown()
        try {
            executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val SHUTDOWN_TIMEOUT_MS = 1_000L
    }
}
