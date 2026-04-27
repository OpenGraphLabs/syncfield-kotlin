package io.opengraph.syncfield.writers

import com.google.common.truth.Truth.assertThat
import io.opengraph.syncfield.StreamCapabilities
import io.opengraph.syncfield.SyncFieldVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WritersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `StreamWriter writes one sorted-key JSON object per line`() = runTest {
        val file = File(tmp.root, "ts.jsonl")
        val writer = StreamWriter(file)
        writer.append(frame = 0, monotonicNs = 1_000L, uncertaintyNs = 500L)
        writer.append(frame = 1, monotonicNs = 2_000L, uncertaintyNs = 500L)
        writer.close()

        val lines = file.readLines()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).isEqualTo(
            """{"frame":0,"timestamp_ns":1000,"uncertainty_ns":500}"""
        )
        assertThat(lines[1]).isEqualTo(
            """{"frame":1,"timestamp_ns":2000,"uncertainty_ns":500}"""
        )
        assertThat(writer.count).isEqualTo(2)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SensorWriter sorts keys at top level and inside channels`() = runTest {
        val file = File(tmp.root, "imu.jsonl")
        val writer = SensorWriter(file)
        writer.append(
            frame = 7,
            monotonicNs = 12345L,
            channels = mapOf("gyro_z" to 0.1, "accel_x" to 0.2, "accel_y" to 0.3),
            deviceTimestampNs = 999L,
        )
        writer.close()

        val line = file.readLines().single()
        // Top-level: channels < device_timestamp_ns < frame < timestamp_ns
        // Channel inner: accel_x < accel_y < gyro_z
        assertThat(line).isEqualTo(
            """{"channels":{"accel_x":0.2,"accel_y":0.3,"gyro_z":0.1},""" +
            """"device_timestamp_ns":999,"frame":7,"timestamp_ns":12345}"""
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SensorWriter omits device_timestamp_ns when null`() = runTest {
        val file = File(tmp.root, "imu.jsonl")
        val writer = SensorWriter(file)
        writer.append(frame = 0, monotonicNs = 1L, channels = mapOf("a" to 1))
        writer.close()
        val line = file.readLines().single()
        assertThat(line).doesNotContain("device_timestamp_ns")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `SessionLogWriter records ts kind detail in sorted order`() = runTest {
        val file = File(tmp.root, "session.log")
        val writer = SessionLogWriter(file)
        writer.append(kind = "state", detail = "connected->recording")
        writer.close()
        val line = file.readLines().single()
        assertThat(line).startsWith("""{"detail":"connected->recording","kind":"state","ts":""")
        assertThat(line).endsWith("\"}")
    }

    @Test
    fun `Manifest writes pretty JSON with sorted keys`() {
        val manifest = Manifest(
            sdkVersion = SyncFieldVersion.current,
            hostId     = "host-x",
            role       = "single",
            streams    = listOf(
                Manifest.StreamEntry(
                    streamId    = "cam_ego",
                    filePath    = "cam_ego.mp4",
                    frameCount  = 1800,
                    kind        = "video",
                    capabilities = StreamCapabilities(producesFile = true,
                                                       providesAudioTrack = true),
                ),
            ),
        )
        val file = File(tmp.root, "manifest.json")
        ManifestWriter.write(manifest, file)
        val text = file.readText()
        assertThat(text).contains("\"sdk_version\": \"${SyncFieldVersion.current}\"")
        assertThat(text).contains("\"host_id\": \"host-x\"")
        assertThat(text).contains("\"role\": \"single\"")
        assertThat(text).contains("\"file_path\": \"cam_ego.mp4\"")
        assertThat(text).contains("\"frame_count\": 1800")
    }
}
