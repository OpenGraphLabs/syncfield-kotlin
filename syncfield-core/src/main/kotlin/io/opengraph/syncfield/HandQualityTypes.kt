package io.opengraph.syncfield

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class HandSide {
    @SerialName("left")
    Left,
    @SerialName("right")
    Right;

    val wireName: String
        get() = when (this) {
            Left -> "left"
            Right -> "right"
        }
}

@Serializable
enum class FrameEdge {
    @SerialName("left")
    Left,
    @SerialName("right")
    Right,
    @SerialName("top")
    Top,
    @SerialName("bottom")
    Bottom;

    val wireName: String
        get() = when (this) {
            Left -> "left"
            Right -> "right"
            Top -> "top"
            Bottom -> "bottom"
        }
}

sealed class HandQualityEvent {
    abstract val side: HandSide
    abstract val monotonicNs: Long
    abstract val frame: Int

    data class NearEdgeStart(
        override val side: HandSide,
        val edges: Set<FrameEdge>,
        override val monotonicNs: Long,
        override val frame: Int,
    ) : HandQualityEvent()

    data class NearEdgeEnd(
        override val side: HandSide,
        override val monotonicNs: Long,
        override val frame: Int,
    ) : HandQualityEvent()

    data class OutOfFrameStart(
        override val side: HandSide,
        override val monotonicNs: Long,
        override val frame: Int,
    ) : HandQualityEvent()

    data class OutOfFrameEnd(
        override val side: HandSide,
        override val monotonicNs: Long,
        override val frame: Int,
    ) : HandQualityEvent()
}

@Serializable
data class QualityStats(
    @SerialName("hand_in_frame_pct")
    val handInFramePct: Double,
    @SerialName("left_in_frame_pct")
    val leftInFramePct: Double,
    @SerialName("right_in_frame_pct")
    val rightInFramePct: Double,
    @SerialName("near_edge_event_count")
    val nearEdgeEventCount: Int,
    @SerialName("out_of_frame_event_count")
    val outOfFrameEventCount: Int,
    @SerialName("out_of_frame_total_seconds")
    val outOfFrameTotalSeconds: Double,
    @SerialName("recording_duration_seconds")
    val recordingDurationSeconds: Double,
)
