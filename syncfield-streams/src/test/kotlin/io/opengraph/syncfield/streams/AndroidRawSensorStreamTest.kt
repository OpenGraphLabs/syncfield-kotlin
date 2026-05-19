package io.opengraph.syncfield.streams

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for the three raw IMU stream classes that mirror the iOS
 * SDK's `iPhoneRawAccelStream` / `iPhoneRawGyroStream` / `iPhoneRawMagStream`.
 *
 * These tests cover the *pure* shape of each stream — defaults, channel
 * mapping, and capabilities — without exercising real `SensorManager`
 * delivery. Live sensor wiring is verified on-device as part of the
 * og-skill recording smoke test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidRawSensorStreamTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    @Test
    fun `accel stream defaults to imu_accel_raw and emits accel_x_y_z channels`() {
        val stream = AndroidRawAccelStream(context)
        assertThat(stream.streamId).isEqualTo("imu_accel_raw")
        assertThat(stream.capabilities.producesFile).isFalse()
        assertThat(stream.capabilities.requiresIngest).isFalse()
        assertThat(stream.capabilities.supportsPreciseTimestamps).isTrue()

        val ch = stream.buildChannels(floatArrayOf(0.1f, -0.2f, 9.81f))
        assertThat(ch.keys).containsExactly("accel_x", "accel_y", "accel_z")
        assertThat(ch["accel_x"]).isEqualTo(0.1f)
        assertThat(ch["accel_y"]).isEqualTo(-0.2f)
        assertThat(ch["accel_z"]).isEqualTo(9.81f)
    }

    @Test
    fun `gyro stream defaults to imu_gyro_raw and emits gyro_x_y_z channels`() {
        val stream = AndroidRawGyroStream(context)
        assertThat(stream.streamId).isEqualTo("imu_gyro_raw")

        val ch = stream.buildChannels(floatArrayOf(0.01f, -0.02f, 0.03f))
        assertThat(ch.keys).containsExactly("gyro_x", "gyro_y", "gyro_z")
        assertThat(ch["gyro_x"]).isEqualTo(0.01f)
        assertThat(ch["gyro_y"]).isEqualTo(-0.02f)
        assertThat(ch["gyro_z"]).isEqualTo(0.03f)
    }

    @Test
    fun `mag stream defaults to imu_mag_raw and emits mag_x_y_z channels`() {
        val stream = AndroidRawMagStream(context)
        assertThat(stream.streamId).isEqualTo("imu_mag_raw")

        val ch = stream.buildChannels(floatArrayOf(-22.9f, -211.3f, -851.1f))
        assertThat(ch.keys).containsExactly("mag_x", "mag_y", "mag_z")
        assertThat(ch["mag_x"]).isEqualTo(-22.9f)
        assertThat(ch["mag_y"]).isEqualTo(-211.3f)
        assertThat(ch["mag_z"]).isEqualTo(-851.1f)
    }

    @Test
    fun `custom streamId is respected`() {
        val a = AndroidRawAccelStream(context, streamId = "custom_accel")
        assertThat(a.streamId).isEqualTo("custom_accel")
    }

    @Test
    fun `raw streams do not leak each other's channel keys`() {
        val accel = AndroidRawAccelStream(context).buildChannels(floatArrayOf(1f, 2f, 3f))
        val gyro  = AndroidRawGyroStream(context).buildChannels(floatArrayOf(1f, 2f, 3f))
        val mag   = AndroidRawMagStream(context).buildChannels(floatArrayOf(1f, 2f, 3f))

        // iOS parity: each raw file carries only its sensor's channels.
        // No gravity/gyro/mag bleed-through that would mimic the old
        // fused `imu.jsonl` bug where fields defaulted to 0.0 before the
        // partner sensor's first event arrived.
        assertThat(accel.keys).containsExactly("accel_x", "accel_y", "accel_z")
        assertThat(gyro.keys).containsExactly("gyro_x", "gyro_y", "gyro_z")
        assertThat(mag.keys).containsExactly("mag_x", "mag_y", "mag_z")
    }
}
