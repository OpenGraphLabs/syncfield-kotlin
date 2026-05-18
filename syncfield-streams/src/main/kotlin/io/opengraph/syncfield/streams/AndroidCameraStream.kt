package io.opengraph.syncfield.streams

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Android equivalent of `iPhoneCameraStream`. Built on CameraX so the
 * same code runs on Camera2-capable devices regardless of OEM.
 *
 * Design notes:
 *
 * - The stream owns a [Preview] use case and a [VideoCapture] use case;
 *   both are bound to a host-supplied [LifecycleOwner] once the host UI
 *   provides a [Preview.SurfaceProvider]. Binding with the surface already
 *   attached avoids a CameraX session that starts surface-less and remains
 *   black until the next orientation/session reconfiguration.
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var previewSurfaceProvider: Preview.SurfaceProvider? = null
    @Volatile private var targetRotation: Int = Surface.ROTATION_0

    /**
     * Resolved by [selectAndPinWidestBackCamera] before [configureUseCases]
     * runs. Carries the bind target (logical id) and, when we picked a
     * sub-physical hidden inside a logical multi-camera (e.g. a Galaxy
     * ultra-wide that CameraX doesn't expose at the top level), the
     * physical id we have to pin via Camera2Interop.
     */
    private var pinnedSelection: CameraSelectionResult? = null

    private var recording: Recording? = null
    private var recordingFinalize: CompletableDeferred<VideoRecordEvent.Finalize>? = null
    private var stampWriter: StreamWriter? = null
    @Volatile private var frameCount: Int = 0
    @Volatile private var isRecording: Boolean = false
    @Volatile private var useWidestBackCamera: Boolean = true
    @Volatile private var wideCameraFallbackAttempted: Boolean = false
    @Volatile private var widestZoomRetryCount: Int = 0
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
            selectAndPinWidestBackCamera()
            configureUseCases()
            // We intentionally do NOT bindToLifecycle here. CameraX 1.3.x
            // does not reliably start preview frames when
            // `setSurfaceProvider` is called AFTER `bindToLifecycle` —
            // the camera session is created surface-less and only
            // attaches frames on the next session reconfig (which on
            // Android requires a rotation, recording start, or full
            // rebind). The host-app preview surface arrives later (via
            // [attachPreviewSurfaceProvider] when SyncFieldPreviewView
            // finishes its first layout), so we bind there with the
            // surface already attached and the session boots into the
            // active state directly. As a safety net, [startRecording]
            // also ensures the bind has run.
        }
        healthBus?.publish(HealthEvent.StreamConnected(streamId))
    }

    /**
     * Attach the host-app preview surface and bind CameraX in one
     * step. Called by `SyncFieldPreviewView` once its underlying
     * `PreviewView` has finished first layout. Idempotent — re-attach
     * just swaps the surface without unbinding.
     */
    @MainThread
    fun attachPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider) {
        previewSurfaceProvider = surfaceProvider
        val pv = preview ?: run {
            Log.w(TAG, "attachPreviewSurfaceProvider called before use cases configured; saved for later")
            return
        }
        pv.setSurfaceProvider(surfaceProvider)
        if (camera == null) {
            bindToLifecycle()
            Log.i(TAG, "Surface attached + first bind complete")
        } else {
            Log.i(TAG, "Surface swap on already-bound camera; no rebind")
        }
    }

    /**
     * Force a bind without a preview surface — used when recording is
     * about to start but no preview view ever attached (e.g., the JS
     * tree never mounted `<SyncFieldPreview/>`). Recording still works;
     * we just don't render anything for the user to watch.
     */
    @MainThread
    private fun ensureBound() {
        if (camera != null) return
        if (cameraProvider == null) {
            Log.w(TAG, "ensureBound called before connect(); bind skipped")
            return
        }
        bindToLifecycle()
        Log.i(TAG, "Bind without preview surface (no SyncFieldPreviewView attached)")
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

    @OptIn(ExperimentalCamera2Interop::class)
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

        val pinnedPhysicalId = pinnedSelection?.physicalCameraId
        val rotations = cameraUseCaseTargetRotations(targetRotation)

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(captureResolutionSelector)
        rotations.preview?.let { previewBuilder.setTargetRotation(it) }
        pinnedPhysicalId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
        preview = previewBuilder.build().also { builtPreview ->
            previewSurfaceProvider?.let { builtPreview.setSurfaceProvider(it) }
        }
        Log.i(
            TAG,
            "Configured camera use cases physical=${pinnedPhysicalId ?: "—"} " +
                "surfaceAttached=${previewSurfaceProvider != null}",
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelectorFor(videoSettings))
            .build()
        // Use the explicit Builder so we can attach Camera2Interop when
        // a sub-physical is pinned. `VideoCapture.withOutput(recorder)`
        // is just sugar for `VideoCapture.Builder(recorder).build()`
        // and doesn't expose the builder chain.
        @SuppressLint("RestrictedApi")
        val videoCaptureBuilder = VideoCapture.Builder(recorder)
        pinnedPhysicalId?.let {
            Camera2Interop.Extender(videoCaptureBuilder).setPhysicalCameraId(it)
        }
        videoCapture = videoCaptureBuilder.build().also {
            it.targetRotation = rotations.videoCapture
        }

        val imageAnalysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(analysisResolutionSelector)
            .setTargetRotation(rotations.imageAnalysis)
        pinnedPhysicalId?.let {
            Camera2Interop.Extender(imageAnalysisBuilder).setPhysicalCameraId(it)
        }
        imageAnalysis = imageAnalysisBuilder.build()
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
        val pinned = if (useWidestBackCamera) pinnedSelection else null
        val selectedCameraSelector = pinned?.let { selectorForLogicalCamera(it.logicalCameraId) }
            ?: cameraSelector
        camera = provider.bindToLifecycle(
            lifecycleOwner,
            selectedCameraSelector,
            preview, videoCapture, imageAnalysis,
        )
        widestZoomRetryCount = 0
        Log.i(
            TAG,
            "Bound camera use cases pinnedLogical=${pinned?.logicalCameraId ?: "default"} " +
                "pinnedPhysical=${pinned?.physicalCameraId ?: "—"} " +
                "surfaceAttached=${previewSurfaceProvider != null}",
        )
        applyWidestZoom(camera, reason = "bind")
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorForLogicalCamera(logicalCameraId: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { info ->
                    runCatching { Camera2CameraInfo.from(info).cameraId == logicalCameraId }
                        .getOrDefault(false)
                }
            }
            .build()

    @OptIn(ExperimentalCamera2Interop::class)
    fun setTargetRotation(rotation: Int) {
        if (targetRotation == rotation) return
        val prev = targetRotation
        targetRotation = rotation
        // Diagnostic: log the bound camera identity before and after a
        // rotation. If the user reports that ultra-wide works in
        // portrait but breaks in landscape, the most likely cause is a
        // CameraX-internal rebind that loses our PhysicalCameraId pin,
        // and we'll see the cameraId change here.
        val current = camera
        val cameraId = current?.cameraInfo?.let {
            runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull()
        }
        val zoomState = current?.cameraInfo?.zoomState?.value
        Log.i(
            TAG,
            "setTargetRotation $prev -> $rotation " +
                "boundCameraId=${cameraId ?: "—"} " +
                "pinnedLogical=${pinnedSelection?.logicalCameraId ?: "—"} " +
                "pinnedPhysical=${pinnedSelection?.physicalCameraId ?: "—"} " +
                "zoom=${zoomState?.zoomRatio?.let { "%.2f".format(it) } ?: "—"} " +
                "minZoom=${zoomState?.minZoomRatio?.let { "%.2f".format(it) } ?: "—"}",
        )
        val rotations = cameraUseCaseTargetRotations(rotation)
        rotations.preview?.let { preview?.targetRotation = it }
        imageAnalysis?.targetRotation = rotations.imageAnalysis
        videoCapture?.targetRotation = rotations.videoCapture
        applyWidestZoom(current, reason = "rotation")
    }

    private fun selectAndPinWidestBackCamera() {
        if (!useWidestBackCamera) {
            pinnedSelection = null
            return
        }
        val candidates = enumerateBackCameraCandidates()
        if (candidates.isEmpty()) {
            Log.w(TAG, "No back-camera candidates enumerated; falling back to default selector")
            pinnedSelection = null
            return
        }
        val selection = pickWidestBackCamera(candidates)
        if (selection == null) {
            pinnedSelection = null
            return
        }
        Log.i(
            TAG,
            "Selected widest back camera logical=${selection.logicalCameraId} " +
                "physical=${selection.physicalCameraId ?: "—"} " +
                "effectiveHFOV=${"%.1f".format(selection.effectiveHorizontalFovDegrees)}° " +
                "minZoom=${"%.2f".format(selection.minZoomRatio)}",
        )
        pinnedSelection = selection
    }

    /**
     * Walks the raw Camera2 graph: top-level camera ids plus, for each
     * one, its `PHYSICAL_CAMERA_IDS` sub-physicals. CameraX's
     * `ProcessCameraProvider.availableCameraInfos` only surfaces the
     * top-level (logical) cameras, so on devices where the true
     * ultra-wide is a hidden sub-physical inside a logical multi-camera
     * (e.g. Samsung Galaxy) we'd never see it otherwise.
     */
    private fun enumerateBackCameraCandidates(): List<BackCameraCandidate> {
        val cameraManager = runCatching {
            context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        }.getOrNull() ?: return emptyList()

        val topLevelIds = runCatching { cameraManager.cameraIdList.toList() }
            .getOrDefault(emptyList())
        val topLevelIdSet = topLevelIds.toSet()
        val candidates = mutableListOf<BackCameraCandidate>()

        for (topId in topLevelIds) {
            val chars = runCatching { cameraManager.getCameraCharacteristics(topId) }.getOrNull()
                ?: continue
            val facing = chars[CameraCharacteristics.LENS_FACING]
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            candidateFromCharacteristics(topId, owningLogicalId = null, chars = chars)
                ?.also { candidates.add(it); logCandidate(it) }

            // Sub-physical IDs are only exposed on API 28+. The
            // characteristics getter for a sub-physical is on the
            // CameraManager itself (added in API 29 — earlier devices
            // can't query the hidden physicals at all).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) continue
            val physicalIds = runCatching { chars.physicalCameraIds }.getOrDefault(emptySet())
            for (physId in physicalIds) {
                if (physId in topLevelIdSet) continue // also seen as a top-level entry
                val physChars = runCatching {
                    cameraManager.getCameraCharacteristics(physId)
                }.getOrNull() ?: continue
                candidateFromCharacteristics(physId, owningLogicalId = topId, chars = physChars)
                    ?.also { candidates.add(it); logCandidate(it) }
            }
        }
        return candidates
    }

    private fun candidateFromCharacteristics(
        cameraId: String,
        owningLogicalId: String?,
        chars: CameraCharacteristics,
    ): BackCameraCandidate? {
        val focalLengths = chars[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]
        val minFocalLength = focalLengths?.minOrNull()?.takeIf { it > 0f }
        val sensorSize = chars[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]
        val sensorLongEdge = sensorSize?.let { max(it.width, it.height) }?.takeIf { it > 0f }
        val minZoomRatio = readMinZoomRatio(chars)
        if (minFocalLength == null || sensorLongEdge == null) {
            Log.i(
                TAG,
                "back camera id=$cameraId" +
                    (if (owningLogicalId != null) " (sub-physical of $owningLogicalId)" else "") +
                    " skipped — missing characteristics (focal=$minFocalLength sensor=$sensorLongEdge)",
            )
            return null
        }
        val effectiveHfov = effectiveHorizontalFovDegrees(
            minFocalLengthMm = minFocalLength.toDouble(),
            sensorLongEdgeMm = sensorLongEdge.toDouble(),
            minZoomRatio = minZoomRatio,
        ) ?: return null
        return BackCameraCandidate(
            cameraId = cameraId,
            owningLogicalCameraId = owningLogicalId,
            effectiveHorizontalFovDegrees = effectiveHfov,
            minZoomRatio = minZoomRatio ?: 1.0,
        )
    }

    private fun readMinZoomRatio(chars: CameraCharacteristics): Double? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val range = runCatching {
            chars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]
        }.getOrNull() ?: return null
        return range.lower.toDouble().takeIf { it > 0.0 }
    }

    private fun logCandidate(c: BackCameraCandidate) {
        val owner = c.owningLogicalCameraId?.let { " (sub-physical of $it)" } ?: " (top-level)"
        Log.i(
            TAG,
            "back camera id=${c.cameraId}$owner " +
                "minZoom=${"%.2f".format(c.minZoomRatio)} " +
                "effectiveHFOV=${"%.1f".format(c.effectiveHorizontalFovDegrees)}°",
        )
    }

    private fun applyWidestZoom(boundCamera: Camera?, reason: String) {
        val camera = boundCamera ?: return

        val zoomState = camera.cameraInfo.zoomState.value
        val stateMinZoom = zoomState?.minZoomRatio
        val selectedMinZoom = pinnedSelection?.minZoomRatio?.toFloat()
        val minZoomRatio = listOfNotNull(stateMinZoom, selectedMinZoom)
            .minOrNull()
            ?: run {
                scheduleWidestZoomRetry(camera, "zoomState pending during $reason")
                return
            }
        if (minZoomRatio >= 1f) return

        runCatching { camera.cameraControl.setZoomRatio(minZoomRatio) }
            .onFailure { Log.w(TAG, "Failed to apply widest zoom=$minZoomRatio during $reason", it) }

        val currentZoom = zoomState?.zoomRatio
        val needsRetry = zoomState == null || currentZoom == null || currentZoom > minZoomRatio + 0.01f
        if (needsRetry) scheduleWidestZoomRetry(camera, reason)
    }

    private fun scheduleWidestZoomRetry(camera: Camera, reason: String) {
        if (widestZoomRetryCount >= MAX_WIDEST_ZOOM_RETRIES) return
        widestZoomRetryCount += 1
        val retryNumber = widestZoomRetryCount
        mainHandler.postDelayed({
            if (this.camera === camera) {
                applyWidestZoom(camera, reason = "retry#$retryNumber after $reason")
            }
        }, WIDEST_ZOOM_RETRY_DELAY_MS * retryNumber)
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
                    // Drop the pinned sub-physical (if any) before rebuilding
                    // use cases; otherwise Camera2Interop would still route
                    // the rebind to the failing lens.
                    pinnedSelection = null
                    configureUseCases()
                    camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview, videoCapture, imageAnalysis,
                    )
                    widestZoomRetryCount = 0
                    Log.i(
                        TAG,
                        "Default back camera fallback bound surfaceAttached=${previewSurfaceProvider != null}",
                    )
                    applyWidestZoom(camera, reason = "black-frame-fallback")
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

        // Fallback bind if no SyncFieldPreviewView ever attached: the
        // VideoCapture use case is built but not yet bound to the
        // CameraX lifecycle until either a preview surface arrives or
        // we get here. Without this, prepareRecording works against a
        // detached recorder.
        withContext(Dispatchers.Main) { ensureBound() }

        val capture = videoCapture ?: throw StreamError(streamId,
            IllegalStateException("videoCapture not initialised — connect() must run first"))

        val started = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<VideoRecordEvent.Finalize>()
        recordingFinalize = finalized
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
                        if (!finalized.isCompleted) finalized.complete(event)
                        if (!started.isCompleted) {
                            val error = event.error
                            if (error == VideoRecordEvent.Finalize.ERROR_NONE) {
                                started.complete(Unit)
                            } else {
                                started.completeExceptionally(
                                    IllegalStateException(
                                        "Camera recording finalized before start, error=$error",
                                        event.cause,
                                    ),
                                )
                            }
                        }
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
        val activeRecording = recording
        recording = null
        if (activeRecording != null) {
            withContext(Dispatchers.Main) {
                runCatching { activeRecording.stop() }
                    .onFailure { Log.w(TAG, "CameraX recording stop request failed", it) }
            }
        }

        val finalizeEvent = recordingFinalize?.let { finalize ->
            withTimeoutOrNull(VIDEO_FINALIZE_TIMEOUT_MS) { finalize.await() }
        }
        recordingFinalize = null
        if (activeRecording != null && finalizeEvent == null) {
            Log.w(TAG, "Timed out waiting for CameraX recording finalize")
        }
        val finalizeError = finalizeEvent?.error ?: VideoRecordEvent.Finalize.ERROR_NONE
        if (finalizeError != VideoRecordEvent.Finalize.ERROR_NONE &&
            finalizeError != VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED &&
            finalizeError != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
        ) {
            throw IllegalStateException(
                "Camera recording finalize failed, error=$finalizeError",
                finalizeEvent?.cause,
            )
        }

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
            previewSurfaceProvider = null
            recording = null
            recordingFinalize = null
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
        const val MAX_WIDEST_ZOOM_RETRIES = 6
        const val WIDEST_ZOOM_RETRY_DELAY_MS = 120L
        const val VIDEO_FINALIZE_TIMEOUT_MS = 15_000L
    }
}
