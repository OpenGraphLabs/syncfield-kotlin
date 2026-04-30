package io.opengraph.syncfield

import io.opengraph.syncfield.writers.EventHandle
import io.opengraph.syncfield.writers.EventWriter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.sqrt

data class NormalizedPoint(val x: Double, val y: Double)

/**
 * Detector-neutral view of one observed hand. Host apps can build this
 * from MediaPipe, ML Kit, Vision or any other hand-pose source.
 */
data class HandObservation(
    val chirality: HandSide?,
    val chiralityConfidence: Double,
    val confidentKeypoints: List<NormalizedPoint>,
    val wrist: NormalizedPoint?,
)

/**
 * Per-hand field-of-view state machine. It is serialised through a mutex
 * to match the Swift actor's behaviour while staying friendly to JVM tests.
 */
class HandQualityMonitor(
    private val config: HandQualityConfig,
    recordingStartMonotonicNs: Long,
    private val eventWriter: EventWriter,
) {
    enum class State { InFrame, NearEdge, OutOfFrame }

    private data class SideState(
        var state: State = State.InFrame,
        var pendingState: State = State.InFrame,
        var pendingSinceNs: Long = 0,
        var lastWrist: NormalizedPoint? = null,
        var wristMemoryExpiresNs: Long = 0,
        var openNearEdgeHandle: EventHandle? = null,
        var openOofHandle: EventHandle? = null,
        var nearEdgeAccumulatedNs: Long = 0,
        var oofAccumulatedNs: Long = 0,
        var lastNearEdgeStartNs: Long = 0,
        var lastOofStartNs: Long = 0,
        var nearEdgeCount: Int = 0,
        var oofCount: Int = 0,
    )

    private val mutex = Mutex()
    private val eventChannel = Channel<HandQualityEvent>(Channel.UNLIMITED)

    val events: Flow<HandQualityEvent> = eventChannel.receiveAsFlow()

    private var leftState = SideState(pendingSinceNs = recordingStartMonotonicNs)
    private var rightState = SideState(pendingSinceNs = recordingStartMonotonicNs)
    private val startupGraceUntilNs =
        recordingStartMonotonicNs + config.startupGraceMs.toLong() * 1_000_000L

    suspend fun ingest(
        observations: List<HandObservation>,
        frame: Int,
        monotonicNs: Long,
    ) = mutex.withLock {
        if (!config.enabled) return@withLock
        val (leftObs, rightObs) = assignToSides(observations)
        applySide(HandSide.Left, leftObs, frame, monotonicNs)
        applySide(HandSide.Right, rightObs, frame, monotonicNs)
    }

    suspend fun finalize(stopMonotonicNs: Long, stopFrame: Int) = mutex.withLock {
        closeIntervalsIfOpen(HandSide.Left, stopMonotonicNs, stopFrame)
        closeIntervalsIfOpen(HandSide.Right, stopMonotonicNs, stopFrame)
        eventWriter.finalize(stopMonotonicNs, stopFrame)
        eventChannel.close()
    }

    suspend fun qualityStats(
        recordingStartMonotonicNs: Long,
        stopMonotonicNs: Long,
    ): QualityStats = mutex.withLock {
        val durationNs = stopMonotonicNs - recordingStartMonotonicNs
        val durationSec = durationNs.toDouble() / 1_000_000_000.0
        var leftOofNs = leftState.oofAccumulatedNs
        var rightOofNs = rightState.oofAccumulatedNs
        if (leftState.state == State.OutOfFrame) {
            leftOofNs += stopMonotonicNs - leftState.lastOofStartNs
        }
        if (rightState.state == State.OutOfFrame) {
            rightOofNs += stopMonotonicNs - rightState.lastOofStartNs
        }
        val leftOofSec = leftOofNs.toDouble() / 1_000_000_000.0
        val rightOofSec = rightOofNs.toDouble() / 1_000_000_000.0
        val leftPct = if (durationSec > 0) max(0.0, 1.0 - leftOofSec / durationSec) else 1.0
        val rightPct = if (durationSec > 0) max(0.0, 1.0 - rightOofSec / durationSec) else 1.0
        QualityStats(
            handInFramePct = minOf(leftPct, rightPct),
            leftInFramePct = leftPct,
            rightInFramePct = rightPct,
            nearEdgeEventCount = leftState.nearEdgeCount + rightState.nearEdgeCount,
            outOfFrameEventCount = leftState.oofCount + rightState.oofCount,
            outOfFrameTotalSeconds = leftOofSec + rightOofSec,
            recordingDurationSeconds = durationSec,
        )
    }

    private fun assignToSides(
        observations: List<HandObservation>,
    ): Pair<HandObservation?, HandObservation?> {
        val allConfident = observations.isNotEmpty() && observations.all {
            it.chirality != null && it.chiralityConfidence >= config.chiralityConfidenceMin
        }
        if (allConfident) {
            return observations.firstOrNull { it.chirality == HandSide.Left } to
                observations.firstOrNull { it.chirality == HandSide.Right }
        }

        if (!config.spatialContinuityFallback || observations.isEmpty()) return null to null
        if (observations.size == 1) {
            val one = observations[0]
            if (one.chirality != null &&
                one.chiralityConfidence >= config.chiralityConfidenceMin
            ) {
                return if (one.chirality == HandSide.Left) one to null else null to one
            }
            val wrist = one.wrist ?: return null to null
            val dl = leftState.lastWrist?.let { distance(it, wrist) } ?: Double.POSITIVE_INFINITY
            val dr = rightState.lastWrist?.let { distance(it, wrist) } ?: Double.POSITIVE_INFINITY
            return if (dl < dr) one to null else null to one
        }

        val a = observations[0]
        val b = observations[1]
        val aw = a.wrist ?: NormalizedPoint(0.0, 0.0)
        val bw = b.wrist ?: NormalizedPoint(0.0, 0.0)
        val costAtoL = leftState.lastWrist?.let { distance(it, aw) } ?: 1.0
        val costAtoR = rightState.lastWrist?.let { distance(it, aw) } ?: 1.0
        val costBtoL = leftState.lastWrist?.let { distance(it, bw) } ?: 1.0
        val costBtoR = rightState.lastWrist?.let { distance(it, bw) } ?: 1.0
        return if (costAtoL + costBtoR <= costAtoR + costBtoL) a to b else b to a
    }

    private fun distance(a: NormalizedPoint, b: NormalizedPoint): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private suspend fun applySide(
        side: HandSide,
        observation: HandObservation?,
        frame: Int,
        monotonicNs: Long,
    ) {
        val inGrace = monotonicNs < startupGraceUntilNs
        val status = instantaneousStatus(observation)
        val state = if (side == HandSide.Left) leftState else rightState

        if (observation?.wrist != null) {
            state.lastWrist = observation.wrist
            state.wristMemoryExpiresNs = monotonicNs + config.wristMemoryMs.toLong() * 1_000_000L
        } else if (monotonicNs > state.wristMemoryExpiresNs) {
            state.lastWrist = null
        }

        if (!inGrace) {
            if (status != state.pendingState) {
                state.pendingState = status
                state.pendingSinceNs = monotonicNs
            }
            val dwellNs = monotonicNs - state.pendingSinceNs
            val oofDebounceNs = config.oofDebounceMs.toLong() * 1_000_000L
            val recoveryDebounceNs = config.recoveryDebounceMs.toLong() * 1_000_000L

            when (state.state to state.pendingState) {
                State.InFrame to State.NearEdge ->
                    transition(side, state, State.NearEdge, monotonicNs, frame)
                State.InFrame to State.OutOfFrame,
                State.NearEdge to State.OutOfFrame ->
                    if (dwellNs >= oofDebounceNs) {
                        transition(side, state, State.OutOfFrame, monotonicNs, frame)
                    }
                State.NearEdge to State.InFrame,
                State.OutOfFrame to State.InFrame,
                State.OutOfFrame to State.NearEdge ->
                    if (dwellNs >= recoveryDebounceNs) {
                        transition(side, state, state.pendingState, monotonicNs, frame)
                    }
            }
        } else if (status != state.pendingState) {
            state.pendingState = status
            state.pendingSinceNs = monotonicNs
        }

        if (side == HandSide.Left) leftState = state else rightState = state
    }

    private fun instantaneousStatus(observation: HandObservation?): State {
        val obs = observation ?: return State.OutOfFrame
        if (obs.confidentKeypoints.size < config.minConfidentKeypointsForBbox) {
            return State.OutOfFrame
        }
        val xs = obs.confidentKeypoints.map { it.x }
        val ys = obs.confidentKeypoints.map { it.y }
        val minX = xs.minOrNull() ?: 0.0
        val maxX = xs.maxOrNull() ?: 1.0
        val minY = ys.minOrNull() ?: 0.0
        val maxY = ys.maxOrNull() ?: 1.0
        val margin = config.proximityWarningExtentNorm
        val near = minX < margin || maxX > 1.0 - margin ||
            minY < margin || maxY > 1.0 - margin
        return if (near) State.NearEdge else State.InFrame
    }

    private suspend fun transition(
        side: HandSide,
        state: SideState,
        newState: State,
        monotonicNs: Long,
        frame: Int,
    ) {
        val old = state.state
        state.state = newState

        if (old == State.NearEdge) {
            state.openNearEdgeHandle?.let {
                eventWriter.closeInterval(it, monotonicNs, frame)
                state.nearEdgeAccumulatedNs += monotonicNs - state.lastNearEdgeStartNs
                eventChannel.trySend(HandQualityEvent.NearEdgeEnd(side, monotonicNs, frame))
            }
            state.openNearEdgeHandle = null
        }

        if (old == State.OutOfFrame) {
            state.openOofHandle?.let {
                eventWriter.closeInterval(it, monotonicNs, frame)
                state.oofAccumulatedNs += monotonicNs - state.lastOofStartNs
                eventChannel.trySend(HandQualityEvent.OutOfFrameEnd(side, monotonicNs, frame))
            }
            state.openOofHandle = null
        }

        if (newState == State.NearEdge) {
            val handle = eventWriter.appendIntervalStart(
                kind = "hand_near_edge",
                startMonotonicNs = monotonicNs,
                startFrame = frame,
                payload = mapOf("hand" to side.wireName),
            )
            state.openNearEdgeHandle = handle
            state.lastNearEdgeStartNs = monotonicNs
            state.nearEdgeCount += 1
            eventChannel.trySend(
                HandQualityEvent.NearEdgeStart(side, emptySet(), monotonicNs, frame)
            )
        }

        if (newState == State.OutOfFrame) {
            val handle = eventWriter.appendIntervalStart(
                kind = "hand_out_of_frame",
                startMonotonicNs = monotonicNs,
                startFrame = frame,
                payload = mapOf("hand" to side.wireName),
            )
            state.openOofHandle = handle
            state.lastOofStartNs = monotonicNs
            state.oofCount += 1
            eventChannel.trySend(HandQualityEvent.OutOfFrameStart(side, monotonicNs, frame))
        }
    }

    private suspend fun closeIntervalsIfOpen(side: HandSide, monotonicNs: Long, frame: Int) {
        val state = if (side == HandSide.Left) leftState else rightState
        state.openNearEdgeHandle?.let {
            eventWriter.closeInterval(it, monotonicNs, frame)
            state.openNearEdgeHandle = null
            state.nearEdgeAccumulatedNs += monotonicNs - state.lastNearEdgeStartNs
            eventChannel.trySend(HandQualityEvent.NearEdgeEnd(side, monotonicNs, frame))
        }
        state.openOofHandle?.let {
            eventWriter.closeInterval(it, monotonicNs, frame)
            state.openOofHandle = null
            state.oofAccumulatedNs += monotonicNs - state.lastOofStartNs
            eventChannel.trySend(HandQualityEvent.OutOfFrameEnd(side, monotonicNs, frame))
        }
        if (side == HandSide.Left) leftState = state else rightState = state
    }
}
