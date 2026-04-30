package io.opengraph.syncfield

import com.google.common.truth.Truth.assertThat
import io.opengraph.syncfield.writers.SyncFieldJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HandQualitySummaryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `verdict mapping follows configured thresholds`() {
        val cfg = HandQualityConfig.Default

        assertThat(
            HandQualitySummaryBuilder.build(
                stats = QualityStats(0.97, 0.97, 0.99, 0, 0, 0.0, 100.0),
                config = cfg,
            ).verdict
        ).isEqualTo(HandQualitySummary.Verdict.Good)

        assertThat(
            HandQualitySummaryBuilder.build(
                stats = QualityStats(0.65, 0.70, 0.95, 4, 6, 35.0, 100.0),
                config = cfg,
            ).verdict
        ).isEqualTo(HandQualitySummary.Verdict.Reject)

        assertThat(
            HandQualitySummaryBuilder.build(
                stats = QualityStats(0.87, 0.90, 0.85, 1, 1, 5.0, 100.0),
                config = cfg,
            ).verdict
        ).isEqualTo(HandQualitySummary.Verdict.Marginal)
    }

    @Test
    fun `write produces parsable snake-case json`() {
        val summary = HandQualitySummaryBuilder.build(
            stats = QualityStats(0.90, 0.90, 0.95, 1, 1, 3.0, 30.0),
            config = HandQualityConfig.Default,
        )
        val file = File(tmp.root, "hand_quality.json")

        HandQualitySummaryBuilder.write(summary, file)

        val parsed = SyncFieldJson.decodeFromString(HandQualitySummary.serializer(), file.readText())
        assertThat(parsed.verdict).isEqualTo(HandQualitySummary.Verdict.Marginal)
        assertThat(parsed.raw.recordingDurationSeconds).isWithin(0.01).of(30.0)

        val json = Json.parseToJsonElement(file.readText()).jsonObject
        assertThat(json["overall_score"]).isNotNull()
        val sub = json["sub_scores"]!!.jsonObject
        assertThat(sub["left_in_frame_pct"]?.jsonPrimitive?.double).isEqualTo(0.9)
        assertThat(sub["both_in_frame_pct"]).isNotNull()
        val raw = json["raw"]!!.jsonObject
        assertThat(raw["hand_in_frame_pct"]).isNotNull()
        assertThat(raw["near_edge_event_count"]).isNotNull()
    }
}
