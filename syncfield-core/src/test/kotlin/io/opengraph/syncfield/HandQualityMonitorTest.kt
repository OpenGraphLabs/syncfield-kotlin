package io.opengraph.syncfield

import com.google.common.truth.Truth.assertThat
import io.opengraph.syncfield.writers.EventWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HandQualityMonitorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val frameStepNs = 100_000_000L

    private fun writer() = EventWriter(File(tmp.root, "events.jsonl"))

    private fun centered(side: HandSide) = HandObservation(
        chirality = side,
        chiralityConfidence = 0.95,
        confidentKeypoints = List(10) { NormalizedPoint(0.5, 0.5) },
        wrist = NormalizedPoint(0.5, 0.5),
    )

    private fun edge(side: HandSide) = HandObservation(
        chirality = side,
        chiralityConfidence = 0.95,
        confidentKeypoints = List(10) { NormalizedPoint(0.05, 0.5) },
        wrist = NormalizedPoint(0.05, 0.5),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `in-frame stable emits no events`() = runTest {
        val monitor = HandQualityMonitor(HandQualityConfig.Default, 0L, writer())
        val events = async { monitor.events.toList() }

        for (i in 0 until 30) {
            val ts = i * frameStepNs + 2_000_000_000L
            monitor.ingest(listOf(centered(HandSide.Left), centered(HandSide.Right)), i, ts)
        }
        monitor.finalize(30 * frameStepNs + 2_000_000_000L, stopFrame = 30)

        assertThat(events.await()).isEmpty()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `near-edge entry emits immediately`() = runTest {
        val monitor = HandQualityMonitor(HandQualityConfig.Default, 0L, writer())
        val events = async { monitor.events.toList() }
        val base = 2_000_000_000L

        for (i in 0 until 3) {
            monitor.ingest(listOf(centered(HandSide.Left), centered(HandSide.Right)), i, base + i * frameStepNs)
        }
        monitor.ingest(listOf(edge(HandSide.Left), centered(HandSide.Right)), 3, base + 3 * frameStepNs)
        monitor.finalize(base + 4 * frameStepNs, stopFrame = 4)

        assertThat(events.await().any { it is HandQualityEvent.NearEdgeStart && it.side == HandSide.Left })
            .isTrue()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `single-frame drop is suppressed by out-of-frame debounce`() = runTest {
        val monitor = HandQualityMonitor(HandQualityConfig.Default, 0L, writer())
        val events = async { monitor.events.toList() }
        val base = 2_000_000_000L

        monitor.ingest(listOf(centered(HandSide.Left), centered(HandSide.Right)), 0, base)
        monitor.ingest(listOf(centered(HandSide.Right)), 1, base + frameStepNs)
        monitor.ingest(listOf(centered(HandSide.Left), centered(HandSide.Right)), 2, base + 2 * frameStepNs)
        monitor.finalize(base + 3 * frameStepNs, stopFrame = 3)

        assertThat(events.await().any { it is HandQualityEvent.OutOfFrameStart }).isFalse()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `continuous absence fires out-of-frame after debounce`() = runTest {
        val cfg = HandQualityConfig.Default.copy(oofDebounceMs = 200)
        val monitor = HandQualityMonitor(cfg, 0L, writer())
        val events = async { monitor.events.toList() }
        val base = 2_000_000_000L

        monitor.ingest(listOf(centered(HandSide.Left), centered(HandSide.Right)), 0, base)
        for (i in 1..4) {
            monitor.ingest(listOf(centered(HandSide.Right)), i, base + i * frameStepNs)
        }
        monitor.finalize(base + 5 * frameStepNs, stopFrame = 5)

        assertThat(events.await().any {
            it is HandQualityEvent.OutOfFrameStart && it.side == HandSide.Left
        }).isTrue()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `quality stats include open out-of-frame interval`() = runTest {
        val monitor = HandQualityMonitor(HandQualityConfig.Default, 2_000_000_000L, writer())
        val base = 3_000_000_000L

        for (i in 0 until 10) {
            monitor.ingest(listOf(centered(HandSide.Left), centered(HandSide.Right)), i, base + i * frameStepNs)
        }
        for (i in 10 until 15) {
            monitor.ingest(listOf(centered(HandSide.Right)), i, base + i * frameStepNs)
        }

        val stop = base + 15 * frameStepNs
        val stats = monitor.qualityStats(base, stop)
        monitor.finalize(stop, stopFrame = 15)

        assertThat(stats.recordingDurationSeconds).isWithin(0.01).of(1.5)
        assertThat(stats.leftInFramePct).isGreaterThan(0.5)
        assertThat(stats.leftInFramePct).isLessThan(0.85)
        assertThat(stats.rightInFramePct).isWithin(0.01).of(1.0)
    }
}
