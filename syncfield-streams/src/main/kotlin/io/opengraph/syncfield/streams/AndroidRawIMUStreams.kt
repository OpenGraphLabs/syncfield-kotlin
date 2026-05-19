package io.opengraph.syncfield.streams

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import io.opengraph.syncfield.HealthBus
import io.opengraph.syncfield.HealthEvent
import io.opengraph.syncfield.SessionClock
import io.opengraph.syncfield.StreamCapabilities
import io.opengraph.syncfield.StreamConnectContext
import io.opengraph.syncfield.StreamError
import io.opengraph.syncfield.StreamIngestReport
import io.opengraph.syncfield.StreamStopReport
import io.opengraph.syncfield.SyncFieldStream
import io.opengraph.syncfield.writers.SensorWriter
import io.opengraph.syncfield.writers.WriterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Shared lifecycle for the three single-sensor raw IMU streams that
 * mirror the iOS SDK's `iPhoneRawAccelStream` / `iPhoneRawGyroStream`
 * / `iPhoneRawMagStream`. Each concrete subclass binds to a single
 * `Sensor.TYPE_*` and writes a row with three channels (`x`/`y`/`z`)
 * per event. There is **no fusion** with other sensors — that is what
 * differentiates raw streams from [AndroidDeviceMotionStream].
 *
 * Channel keys are derived from [channelPrefix], so `"accel"` yields
 * `accel_x`, `accel_y`, `accel_z` and so on. `SensorEvent.timestamp`
 * (monotonic, nanoseconds) is copied straight into `capture_ns`.
 */
abstract class AndroidRawSensorStream internal constructor(
    private val context: Context,
    override val streamId: String,
    private val sensorType: Int,
    private val channelPrefix: String,
    private val rateHz: Int,
) : SyncFieldStream {

    override val capabilities = StreamCapabilities(
        requiresIngest = false,
        producesFile = false,
        supportsPreciseTimestamps = true,
    )

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sensor: Sensor? = sensorManager.getDefaultSensor(sensorType)

    private val handlerThread = HandlerThread(
        "syncfield.imu.$streamId",
        Process.THREAD_PRIORITY_URGENT_DISPLAY,
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var writer: SensorWriter? = null
    private var clock: SessionClock? = null
    private var healthBus: HealthBus? = null
    private var listener: SensorEventListener? = null
    @Volatile private var frameCount: Int = 0

    /**
     * Maps the three-axis sensor reading to the channel map written
     * to disk. Exposed (not private) so unit tests can verify the
     * schema without standing up a real `SensorManager`.
     */
    internal fun buildChannels(values: FloatArray): Map<String, Any?> {
        // SensorManager events for accel / gyro / mag always carry at
        // least 3 values; uncalibrated variants would carry 6, but we
        // intentionally only read the first 3 to match iOS.
        require(values.size >= 3) { "expected >=3 sensor values, got ${values.size}" }
        return mapOf(
            "${channelPrefix}_x" to values[0],
            "${channelPrefix}_y" to values[1],
            "${channelPrefix}_z" to values[2],
        )
    }

    override suspend fun prepare() {
        if (sensor == null) {
            throw StreamError(
                streamId,
                IllegalStateException(
                    "device has no sensor of type=$sensorType for stream=$streamId",
                ),
            )
        }
    }

    override suspend fun connect(context: StreamConnectContext) {
        this.healthBus = context.healthBus
        if (!handlerThread.isAlive) handlerThread.start()
        healthBus?.publish(HealthEvent.StreamConnected(streamId))
    }

    override suspend fun startRecording(clock: SessionClock, writerFactory: WriterFactory) {
        this.clock = clock
        this.writer = writerFactory.makeSensorWriter(streamId)
        this.frameCount = 0

        val s = sensor ?: return
        val handler = Handler(handlerThread.looper)
        val periodUs = 1_000_000 / rateHz
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != sensorType) return
                val w = writer ?: return
                val frame = frameCount
                frameCount += 1
                val channels = buildChannels(event.values)
                ioScope.launch {
                    runCatching {
                        w.append(frame = frame, monotonicNs = event.timestamp,
                                 channels = channels)
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = l
        sensorManager.registerListener(l, s, periodUs, handler)
    }

    override suspend fun stopRecording(): StreamStopReport {
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
        val n = frameCount
        runCatching { writer?.close() }
        writer = null
        return StreamStopReport(streamId, frameCount = n, kind = "sensor")
    }

    override suspend fun ingest(
        episodeDirectory: File,
        progress: (Double) -> Unit,
    ): StreamIngestReport = StreamIngestReport(streamId, "$streamId.jsonl", frameCount)

    override suspend fun disconnect() {
        runCatching { handlerThread.quitSafely() }
        // Mirrors AndroidDeviceMotionStream: writer was already closed
        // in stopRecording; cancelling the scope just frees the
        // SupervisorJob — no data path needs draining here.
        ioScope.cancel()
        healthBus?.publish(HealthEvent.StreamDisconnected(streamId, "normal"))
    }
}

/**
 * Raw accelerometer stream (gravity *included*). Equivalent to iOS
 * `iPhoneRawAccelStream` and `imu_accel_raw.jsonl`. Uses
 * `Sensor.TYPE_ACCELEROMETER`, NOT `TYPE_LINEAR_ACCELERATION` —
 * VIO/SLAM consumers need raw specific-force to recover gravity and
 * metric scale.
 */
class AndroidRawAccelStream(
    context: Context,
    streamId: String = "imu_accel_raw",
    rateHz: Int = 100,
) : AndroidRawSensorStream(
    context = context,
    streamId = streamId,
    sensorType = Sensor.TYPE_ACCELEROMETER,
    channelPrefix = "accel",
    rateHz = rateHz,
)

/**
 * Raw gyroscope stream. Equivalent to iOS `iPhoneRawGyroStream` and
 * `imu_gyro_raw.jsonl`. Uses `Sensor.TYPE_GYROSCOPE` (calibrated;
 * `TYPE_GYROSCOPE_UNCALIBRATED` is a future option if drift bias
 * tracking is needed).
 */
class AndroidRawGyroStream(
    context: Context,
    streamId: String = "imu_gyro_raw",
    rateHz: Int = 100,
) : AndroidRawSensorStream(
    context = context,
    streamId = streamId,
    sensorType = Sensor.TYPE_GYROSCOPE,
    channelPrefix = "gyro",
    rateHz = rateHz,
)

/**
 * Raw magnetometer stream. Equivalent to iOS `iPhoneRawMagStream` and
 * `imu_mag_raw.jsonl`. Uses `Sensor.TYPE_MAGNETIC_FIELD` (μT). This
 * stream is what lets downstream VIO bind absolute yaw to magnetic
 * north and stops drift over long recordings.
 */
class AndroidRawMagStream(
    context: Context,
    streamId: String = "imu_mag_raw",
    rateHz: Int = 100,
) : AndroidRawSensorStream(
    context = context,
    streamId = streamId,
    sensorType = Sensor.TYPE_MAGNETIC_FIELD,
    channelPrefix = "mag",
    rateHz = rateHz,
)
