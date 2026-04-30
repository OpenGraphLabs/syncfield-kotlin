package io.opengraph.syncfield

import io.opengraph.syncfield.writers.SyncFieldJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class HandQualitySummary(
    val verdict: Verdict,
    @SerialName("overall_score")
    val overallScore: Double,
    @SerialName("sub_scores")
    val subScores: SubScores,
    val raw: QualityStats,
    val thresholds: Thresholds,
    val config: HandQualityConfig,
) {
    @Serializable
    enum class Verdict {
        @SerialName("good")
        Good,
        @SerialName("marginal")
        Marginal,
        @SerialName("reject")
        Reject,
    }

    @Serializable
    data class SubScores(
        @SerialName("left_in_frame_pct")
        val leftInFramePct: Double,
        @SerialName("right_in_frame_pct")
        val rightInFramePct: Double,
        @SerialName("both_in_frame_pct")
        val bothInFramePct: Double,
    )

    @Serializable
    data class Thresholds(
        val good: Double,
        val reject: Double,
    )
}

object HandQualitySummaryBuilder {
    fun build(stats: QualityStats, config: HandQualityConfig): HandQualitySummary {
        val overall = stats.handInFramePct
        val verdict = when {
            overall >= config.verdictGoodThreshold -> HandQualitySummary.Verdict.Good
            overall < config.verdictRejectThreshold -> HandQualitySummary.Verdict.Reject
            else -> HandQualitySummary.Verdict.Marginal
        }
        return HandQualitySummary(
            verdict = verdict,
            overallScore = overall,
            subScores = HandQualitySummary.SubScores(
                leftInFramePct = stats.leftInFramePct,
                rightInFramePct = stats.rightInFramePct,
                bothInFramePct = stats.handInFramePct,
            ),
            raw = stats,
            thresholds = HandQualitySummary.Thresholds(
                good = config.verdictGoodThreshold,
                reject = config.verdictRejectThreshold,
            ),
            config = config,
        )
    }

    fun write(summary: HandQualitySummary, to: File) {
        val tmp = File(to.parentFile, "${to.name}.tmp")
        tmp.writeText(SyncFieldJson.encodeToString(HandQualitySummary.serializer(), summary))
        if (!tmp.renameTo(to)) {
            tmp.copyTo(to, overwrite = true)
            tmp.delete()
        }
    }
}
