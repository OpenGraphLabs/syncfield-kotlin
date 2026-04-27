package io.opengraph.syncfield.streams

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
 * Android equivalent of `iPhoneMotionStream`. Subscribes to
 * `TYPE_LINEAR_ACCELERATION`, `TYPE_GYROSCOPE`, and `TYPE_GRAVITY`,
 * fuses the latest values into one row per gyroscope event, and
 * writes them via [SensorWriter] in the same channel layout the
 * Swift stream uses (accel_*, gyro_*, gravity_*).
 *
 * `SensorEvent.timestamp` is in the same monotonic domain as
 * `System.nanoTime()` (both are `clock_gettime(CLOCK_MONOTONIC)` under
 * the hood), so we copy it straight into `timestamp_ns` without any
 * conversion. This matches what `iPhoneMotionStream` does on iOS,
 * where `CMDeviceMotion.timestamp` is already in mach absolute time.
 */
class AndroidMotionStream(
    private val context: Context,
    override val streamId: String = "imu",
    private val rateHz: Int = 100,
) : SyncFieldStream {

    override val capabilities = StreamCapabilities(
        requiresIngest = false,
        producesFile = false,
        supportsPreciseTimestamps = true,
    )

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    private val handlerThread = HandlerThread(
        "syncfield.motion.$streamId",
        Process.THREAD_PRIORITY_URGENT_DISPLAY,
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var writer: SensorWriter? = null
    private var clock: SessionClock? = null
    private var healthBus: HealthBus? = null
    private var listener: SensorEventListener? = null
    @Volatile private var frameCount: Int = 0

    @Volatile private var lastAccel: FloatArray = FloatArray(3)
    @Volatile private var lastGyro: FloatArray = FloatArray(3)
    @Volatile private var lastGravity: FloatArray = FloatArray(3)

    override suspend fun prepare() {
        if (accelSensor == null && gyroSensor == null) {
            throw StreamError(
                streamId,
                IllegalStateException("device has neither linear-acceleration nor gyroscope sensors"),
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

        val handler = android.os.Handler(handlerThread.looper)
        val periodUs = 1_000_000 / rateHz
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        lastAccel = event.values.copyOf(3)
                        handle(event)
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        lastGyro = event.values.copyOf(3)
                        handle(event)
                    }
                    Sensor.TYPE_GRAVITY -> {
                        lastGravity = event.values.copyOf(3)
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

            /**
             * Emit one row per gyro-or-accel event. Gyro is the
             * standard sampling driver because it's the highest-rate
             * sensor on most phones; if a device only has accel, we
             * fall through to that sensor instead.
             */
            private fun handle(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_GRAVITY) return
                // Prefer gyro events as the emit driver if present;
                // fall back to accel when the device has no gyro.
                if (gyroSensor != null && event.sensor.type != Sensor.TYPE_GYROSCOPE) return

                val w = writer ?: return
                val frame = frameCount
                frameCount += 1
                val ax = lastAccel; val gy = lastGyro; val gv = lastGravity
                val channels = mapOf(
                    "accel_x"   to ax[0],
                    "accel_y"   to ax[1],
                    "accel_z"   to ax[2],
                    "gyro_x"    to gy[0],
                    "gyro_y"    to gy[1],
                    "gyro_z"    to gy[2],
                    "gravity_x" to gv[0],
                    "gravity_y" to gv[1],
                    "gravity_z" to gv[2],
                )
                ioScope.launch {
                    runCatching {
                        w.append(frame = frame, monotonicNs = event.timestamp,
                                 channels = channels)
                    }
                }
            }
        }
        listener = l

        accelSensor?.let { sensorManager.registerListener(l, it, periodUs, handler) }
        gyroSensor?.let { sensorManager.registerListener(l, it, periodUs, handler) }
        gravitySensor?.let { sensorManager.registerListener(l, it, periodUs, handler) }
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
        // The writer was already closed in stopRecording; any in-flight
        // append launches that captured the writer reference will write
        // into the (now-closed) stream and throw, which the launches'
        // own runCatching swallows. Cancelling the scope just frees the
        // SupervisorJob — no data path needs draining here.
        ioScope.cancel()
        healthBus?.publish(HealthEvent.StreamDisconnected(streamId, "normal"))
    }
}
