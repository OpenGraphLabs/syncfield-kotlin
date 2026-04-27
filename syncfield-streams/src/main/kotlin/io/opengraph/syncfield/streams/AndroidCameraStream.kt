package io.opengraph.syncfield.streams

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.opengraph.syncfield.HealthBus
import io.opengraph.syncfield.HealthEvent
import io.opengraph.syncfield.SessionClock
import io.opengraph.syncfield.StreamCapabilities
import io.opengraph.syncfield.StreamConnectContext
import io.opengraph.syncfield.StreamError
import io.opengraph.syncfield.StreamIngestReport
import io.opengraph.syncfield.StreamStopReport
import io.opengraph.syncfield.SyncFieldStream
import io.opengraph.syncfield.writers.StreamWriter
import io.opengraph.syncfield.writers.WriterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android equivalent of `iPhoneCameraStream`. Built on CameraX so the
 * same code runs on Camera2-capable devices regardless of OEM.
 *
 * Design notes:
 *
 * - The stream owns a [Preview] use case and a [VideoCapture] use case;
 *   both are bound to a host-supplied [LifecycleOwner] at `connect()`
 *   time so the host can attach the preview to a [PreviewView] in the UI.
 * - Per-frame timestamps are written into `<streamId>.timestamps.jsonl`
 *   from a parallel [ImageAnalysis] use case throttled to a no-op-fast
 *   analyser (we only need timestamps, not pixels). The analyser's
 *   `ImageProxy.getImageInfo().timestamp` lives in the
 *   `System.nanoTime()` domain — same as the IMU stream's
 *   `SensorEvent.timestamp` — so post-hoc alignment with motion data
 *   needs no clock conversion.
 * - The host-app frame processor (used for hand detection) is exposed
 *   through [setFrameProcessor]; it reuses the analyser hooks so a
 *   single CameraX pipeline serves both telemetry and analysis.
 */
class AndroidCameraStream(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    override val streamId: String = "cam_ego",
    private val videoSettings: VideoSettings = VideoSettings.HD720_60,
    private val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
) : SyncFieldStream {

    override val capabilities = StreamCapabilities(
        requiresIngest = false,
        producesFile = true,
        supportsPreciseTimestamps = true,
        providesAudioTrack = true,
    )

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null

    private var recording: Recording? = null
    private var stampWriter: StreamWriter? = null
    @Volatile private var frameCount: Int = 0
    @Volatile private var isRecording: Boolean = false

    private var healthBus: HealthBus? = null
    private var outputFile: File? = null
    private var clock: SessionClock? = null

    private var frameProcessor: ((ImageProxy, Int) -> Unit)? = null
    @Volatile private var throttleHz: Double = 0.0
    @Volatile private var lastProcessorCallNs: Long = 0L

    /**
     * Hand-off accessor for [io.opengraph.syncfield.ui.SyncFieldPreviewView]
     * (in `syncfield-ui`) — the preview view binds the underlying
     * `Preview` use case to its surface provider when this property
     * becomes non-null.
     */
    val livePreview: Preview? get() = preview

    override suspend fun prepare() = withContext(Dispatchers.Main) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw StreamError(streamId,
                SecurityException("CAMERA permission not granted"))
        }
    }

    override suspend fun connect(context: StreamConnectContext) {
        healthBus = context.healthBus
        cameraProvider = obtainCameraProvider()
        configureUseCases()
        bindToLifecycle()
        healthBus?.publish(HealthEvent.StreamConnected(streamId))
    }

    private suspend fun obtainCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            }, ContextCompat.getMainExecutor(context))
        }

    private fun configureUseCases() {
        val targetSize = Size(videoSettings.width, videoSettings.height)
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(targetSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()

        preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelectorFor(videoSettings))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolutionSelector)
            .build()
            .also { ia ->
                ia.setAnalyzer(cameraExecutor) { proxy ->
                    onAnalysisFrame(proxy)
                    proxy.close()
                }
            }
    }

    @SuppressLint("RestrictedApi")
    private fun bindToLifecycle() {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview, videoCapture, imageAnalysis,
        )
    }

    override suspend fun startRecording(clock: SessionClock, writerFactory: WriterFactory) {
        this.clock = clock
        stampWriter = writerFactory.makeStreamWriter(streamId)
        frameCount = 0

        val output = writerFactory.videoFile(streamId, "mp4")
        if (output.exists()) output.delete()
        outputFile = output

        val capture = videoCapture ?: throw StreamError(streamId,
            IllegalStateException("videoCapture not initialised — connect() must run first"))

        val outputOptions = FileOutputOptions.Builder(output).build()
        var pendingRecording = capture.output.prepareRecording(context, outputOptions)
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            @SuppressLint("MissingPermission")
            val withAudio = pendingRecording.withAudioEnabled()
            pendingRecording = withAudio
        }

        val started = CompletableDeferred<Unit>()
        recording = pendingRecording.start(cameraExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isRecording = true
                    if (!started.isCompleted) started.complete(Unit)
                }
                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                }
                else -> Unit
            }
        }
        // Wait for the recorder to actually transition to "recording"
        // before we return — the orchestrator's atomic-start contract
        // requires that startRecording either succeeds or rolls back.
        started.await()
    }

    override suspend fun stopRecording(): StreamStopReport {
        isRecording = false
        recording?.stop()
        recording = null

        runCatching { stampWriter?.close() }
        stampWriter = null

        return StreamStopReport(streamId, frameCount = frameCount, kind = "video")
    }

    override suspend fun ingest(
        episodeDirectory: File,
        progress: (Double) -> Unit,
    ): StreamIngestReport = StreamIngestReport(
        streamId = streamId,
        filePath = "$streamId.mp4",
        frameCount = frameCount,
    )

    override suspend fun disconnect() {
        withContext(Dispatchers.Main) {
            cameraProvider?.unbindAll()
            cameraProvider = null
            camera = null
            preview = null
            videoCapture = null
            imageAnalysis = null
        }
        healthBus?.publish(HealthEvent.StreamDisconnected(streamId, "normal"))
    }

    /**
     * Frame analysis hook reused by host apps for things like hand
     * detection. Throttled internally to [throttleHz]; pass `0` to
     * disable throttling.
     */
    fun setFrameProcessor(throttleHz: Double = 0.0, body: (ImageProxy, Int) -> Unit) {
        this.throttleHz = throttleHz
        this.frameProcessor = body
    }

    fun clearFrameProcessor() {
        frameProcessor = null
        throttleHz = 0.0
    }

    private fun onAnalysisFrame(proxy: ImageProxy) {
        val tsNs = proxy.imageInfo.timestamp

        // Frame processor — runs whether or not we're recording so
        // previews + hand detection keep working pre-record.
        val processor = frameProcessor
        if (processor != null) {
            val intervalNs = if (throttleHz > 0.0) (1_000_000_000.0 / throttleHz).toLong() else 0L
            if (intervalNs == 0L || tsNs - lastProcessorCallNs >= intervalNs) {
                processor(proxy, frameCount)
                lastProcessorCallNs = tsNs
            }
        }

        if (!isRecording) return
        val w = stampWriter ?: return
        val frame = frameCount
        frameCount += 1
        ioScope.launch {
            runCatching {
                w.append(frame = frame, monotonicNs = tsNs, uncertaintyNs = 1_000_000L)
            }
        }
    }

    private fun qualitySelectorFor(settings: VideoSettings): QualitySelector {
        val q = when {
            settings.height >= 2160 -> Quality.UHD
            settings.height >= 1080 -> Quality.FHD
            settings.height >= 720  -> Quality.HD
            else                    -> Quality.SD
        }
        return QualitySelector.from(q)
    }
}
