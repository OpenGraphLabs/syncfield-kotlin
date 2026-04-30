package io.opengraph.syncfield.streams

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
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
import androidx.lifecycle.ProcessLifecycleOwner
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
import kotlin.math.atan
import kotlin.math.max

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
class AndroidCameraStream @JvmOverloads constructor(
    private val context: Context,
    /**
     * Lifecycle that gates the underlying CameraX use cases. Defaults
     * to [ProcessLifecycleOwner] so callers (the RN bridge module in
     * particular) don't need to plumb an Activity reference through —
     * the camera stays bound for the whole foreground lifetime of the
     * app, which matches how `iPhoneCameraStream`'s `AVCaptureSession`
     * behaves on iOS.
     */
    private val lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(),
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
    @Volatile private var targetRotation: Int = Surface.ROTATION_0

    private var recording: Recording? = null
    private var stampWriter: StreamWriter? = null
    @Volatile private var frameCount: Int = 0
    @Volatile private var isRecording: Boolean = false
    @Volatile private var useWidestBackCamera: Boolean = true
    @Volatile private var wideCameraFallbackAttempted: Boolean = false
    @Volatile private var previewProbeFrameCount: Int = 0
    @Volatile private var blackPreviewFrameCount: Int = 0

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
        withContext(Dispatchers.Main) {
            cameraProvider = obtainCameraProvider()
            configureUseCases()
            bindToLifecycle()
        }
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
        val captureResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(targetSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()
        val analysisResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 360),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()

        preview = Preview.Builder()
            .setResolutionSelector(captureResolutionSelector)
            .setTargetRotation(targetRotation)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelectorFor(videoSettings))
            .build()
        videoCapture = VideoCapture.withOutput(recorder).also {
            it.targetRotation = targetRotation
        }

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(analysisResolutionSelector)
            .setTargetRotation(targetRotation)
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
        val selectedCameraSelector = if (useWidestBackCamera) {
            widestBackCameraSelector(provider) ?: cameraSelector
        } else {
            cameraSelector
        }
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            selectedCameraSelector,
            preview, videoCapture, imageAnalysis,
        )
        applyWidestZoom(camera)
    }

    fun setTargetRotation(rotation: Int) {
        if (targetRotation == rotation) return
        targetRotation = rotation
        preview?.targetRotation = rotation
        imageAnalysis?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    private fun widestBackCameraSelector(provider: ProcessCameraProvider): CameraSelector? {
        val candidates = provider.availableCameraInfos.mapNotNull { info ->
            val camera2Info = runCatching { Camera2CameraInfo.from(info) }.getOrNull()
                ?: return@mapNotNull null
            val lensFacing = runCatching {
                camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            }.getOrNull()
            if (lensFacing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null

            val fov = horizontalFovDegrees(info) ?: return@mapNotNull null
            val cameraId = runCatching { camera2Info.cameraId }.getOrNull()
                ?: return@mapNotNull null
            CameraFovCandidate(cameraId = cameraId, horizontalFovDegrees = fov)
        }

        val widest = candidates.maxByOrNull { it.horizontalFovDegrees } ?: return null
        Log.i(
            TAG,
            "Selected widest back camera id=${widest.cameraId} fov=${"%.1f".format(widest.horizontalFovDegrees)}",
        )
        return CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { info ->
                    runCatching { Camera2CameraInfo.from(info).cameraId == widest.cameraId }
                        .getOrDefault(false)
                }
            }
            .build()
    }

    private fun horizontalFovDegrees(info: CameraInfo): Double? {
        val camera2Info = runCatching { Camera2CameraInfo.from(info) }.getOrNull()
            ?: return null
        val focalLengths = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: return null
        val minFocalLength = focalLengths.minOrNull()?.takeIf { it > 0f } ?: return null
        val sensorSize = camera2Info
            .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        val sensorLongEdge = max(sensorSize.width, sensorSize.height)
        if (sensorLongEdge <= 0f) return null
        return Math.toDegrees(2.0 * atan(sensorLongEdge / (2.0 * minFocalLength)))
    }

    private data class CameraFovCandidate(
        val cameraId: String,
        val horizontalFovDegrees: Double,
    )

    private fun applyWidestZoom(boundCamera: Camera?) {
        val camera = boundCamera ?: return
        val minZoomRatio = camera.cameraInfo.zoomState.value?.minZoomRatio ?: return
        if (minZoomRatio < 1f) {
            runCatching { camera.cameraControl.setZoomRatio(minZoomRatio) }
        }
    }

    private fun maybeFallbackFromBlackWideCamera(proxy: ImageProxy) {
        if (!useWidestBackCamera || wideCameraFallbackAttempted || previewProbeFrameCount >= 45) {
            return
        }

        val avgLuma = averageLuma(proxy) ?: return
        previewProbeFrameCount += 1
        if (avgLuma <= 2.0) {
            blackPreviewFrameCount += 1
        } else {
            blackPreviewFrameCount = 0
        }

        if (previewProbeFrameCount >= 24 && blackPreviewFrameCount >= 24) {
            wideCameraFallbackAttempted = true
            useWidestBackCamera = false
            Log.w(TAG, "Widest back camera produced black frames; falling back to default back camera.")
            ioScope.launch(Dispatchers.Main) {
                runCatching {
                    val provider = cameraProvider ?: return@launch
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview, videoCapture, imageAnalysis,
                    )
                    applyWidestZoom(camera)
                }.onFailure { error ->
                    Log.w(TAG, "Default back camera fallback failed", error)
                }
            }
        }
    }

    private fun averageLuma(proxy: ImageProxy): Double? {
        val buffer = proxy.planes.firstOrNull()?.buffer ?: return null
        val remaining = buffer.remaining()
        if (remaining <= 0) return null

        if (proxy.format == android.graphics.PixelFormat.RGBA_8888 ||
            proxy.planes.firstOrNull()?.pixelStride == 4
        ) {
            val samplePixels = minOf(remaining / 4, 512)
            if (samplePixels <= 0) return null
            val stepPixels = max(1, (remaining / 4) / samplePixels)
            val limit = buffer.limit()
            var index = buffer.position()
            var sum = 0.0
            var count = 0
            while (index + 3 < limit && count < samplePixels) {
                val r = buffer.get(index).toInt() and 0xFF
                val g = buffer.get(index + 1).toInt() and 0xFF
                val b = buffer.get(index + 2).toInt() and 0xFF
                sum += 0.299 * r + 0.587 * g + 0.114 * b
                count += 1
                index += stepPixels * 4
            }
            if (count == 0) return null
            return sum / count.toDouble()
        }

        val sampleCount = minOf(remaining, 512)
        val step = max(1, remaining / sampleCount)
        val limit = buffer.limit()
        var index = buffer.position()
        var sum = 0L
        var count = 0
        while (index < limit && count < sampleCount) {
            sum += (buffer.get(index).toInt() and 0xFF)
            count += 1
            index += step
        }
        if (count == 0) return null
        return sum.toDouble() / count.toDouble()
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

        val started = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            val outputOptions = FileOutputOptions.Builder(output).build()
            var pendingRecording = capture.output.prepareRecording(context, outputOptions)
            val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                @SuppressLint("MissingPermission")
                val withAudio = pendingRecording.withAudioEnabled()
                pendingRecording = withAudio
            }

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
        maybeFallbackFromBlackWideCamera(proxy)
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

    private companion object {
        const val TAG = "AndroidCameraStream"
    }
}
