package dev.ehsanfahad.darkchamber.app.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.media.MediaActionSound
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.ehsanfahad.darkchamber.app.picker.awaitActivityResult
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureResult
import dev.ehsanfahad.darkchamber.feature.camera.api.CapturedPhoto
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import dev.ehsanfahad.darkchamber.feature.camera.api.Recording
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingEnd
import dev.ehsanfahad.darkchamber.feature.camera.api.ReportedRecording
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoContainer
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path
import java.io.File
import kotlin.coroutines.resume
import androidx.camera.core.Preview as PreviewUseCase
import androidx.camera.video.Recording as PlatformRecording

/**
 * CameraX as the [CameraController] seam.
 *
 * **A photo never touches a file.** `takePicture` is called with an in-memory callback, the JPEG
 * plane is copied into a `ByteArray` and the `ImageProxy` closed; the `OutputFileOptions` overload is
 * never used — CameraX's saver stages through a file even when handed a stream (hard rule #1). No
 * location is ever set on a capture, and the app holds no location permission.
 *
 * **A video goes only to the file it is handed** — `FileOutputOptions` over the caller's scratch
 * path in the vault's `tmp/`, with the caller's size limit. Never a `MediaStore` output, never a path
 * of this class's choosing, and the path is never logged. Sound is recorded only when the caller asks
 * for it **and** the microphone is granted.
 *
 * **One mode bound at a time.** Photo binds the preview with `ImageCapture` at 4:3, video binds it
 * with `VideoCapture` at 16:9, so the preview always has the shape of what is saved and the
 * viewfinder shows it whole (`FIT_CENTER`). Switching mode, lens or quality rebinds; none of them is
 * honoured while a recording runs.
 *
 * **Sounds only where the law asks for them.** CameraX plays none itself; where the device's region
 * requires one (`CameraInfo.mustPlayShutterSound`), the system's shutter click is played as each
 * photo starts, and its record-start and record-stop sounds as a recording does. Everywhere else
 * nothing makes a sound of its own.
 *
 * **Bound to a lifecycle of its own**, driven by [start] and [stop], rather than to the activity.
 * The camera screen survives a rotation (the session is retained while the activity is recreated),
 * so binding to "the current activity" would bind to one that is being destroyed, and between the
 * new activity's start and resume there is no current one to find. The screen's own lifecycle is
 * what decides when the camera is open, and that is what calls these two.
 *
 * Main-thread confined, like the seam: every call comes from the screen, CameraX expects its
 * configuration calls on the main thread, and its recording events are delivered there.
 */
class AndroidCameraController(
    private val context: Context,
    private val activity: () -> FragmentActivity?,
) : CameraController {

    private val _permission = MutableStateFlow(CameraPermission.NotAsked)
    override val permission: StateFlow<CameraPermission> = _permission.asStateFlow()

    private val _availability = MutableStateFlow(CameraAvailability.Available)
    override val availability: StateFlow<CameraAvailability> = _availability.asStateFlow()

    private val _capabilities = MutableStateFlow(CameraCapabilities.None)
    override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    private val _microphonePermission = MutableStateFlow(MicrophonePermission.NotAsked)
    override val microphonePermission: StateFlow<MicrophonePermission> = _microphonePermission.asStateFlow()

    // CameraX's Recorder muxes MP4.
    override val videoContainer: VideoContainer = VideoContainer.Mp4

    private val _deviceRotation = MutableStateFlow(0)
    override val deviceRotation: StateFlow<Int> = _deviceRotation.asStateFlow()

    private val owner = SeamLifecycle()

    // Built on first use rather than here: constructing a use case reaches into the framework, and
    // this object is built by the composition root at startup, camera or no camera.
    private val imageCapture by lazy {
        ImageCapture.Builder()
            // A private camera is used for a quick shot; latency matters more than the last bit of
            // quality, and the photo is re-encoded by nobody.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionFor(CaptureMode.Photo))
            .build()
    }

    // Rebuilt on each bind: the preview's shape and the recorder's quality are both fixed when a use
    // case is built.
    private var preview: PreviewUseCase? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    private var provider: ProcessCameraProvider? = null
    private var configured = false
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private var lens = Lens.Back
    private var flash = FlashMode.Off
    private var mode = CaptureMode.Photo
    private var quality = VideoQuality.HD
    private var torch = false
    private var started = false
    private var rotation = Surface.ROTATION_0

    // The recording in progress: the handle the caller holds and CameraX's own, which must stay
    // referenced until it is stopped — CameraX ends a recording whose handle is collected.
    private var active: ActiveRecording? = null

    // Loaded once, on first use, and only where a shutter sound is required.
    private val sounds: MediaActionSound? by lazy {
        if (CameraInfo.mustPlayShutterSound()) {
            MediaActionSound().apply {
                load(MediaActionSound.SHUTTER_CLICK)
                load(MediaActionSound.START_VIDEO_RECORDING)
                load(MediaActionSound.STOP_VIDEO_RECORDING)
            }
        } else {
            null
        }
    }

    // Whether this process has raised each system prompt — see [permissionState]. Memory only.
    private var asked = false
    private var microphoneAsked = false

    // The phone's tilt, so a capture taken sideways is tagged sideways even though the screen holds
    // portrait, and so the screen's icons can turn by the same reading.
    private val tilt by lazy {
        object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val next = surfaceRotation(orientation) ?: return
                rotation = next
                imageCapture.targetRotation = next
                videoCapture?.targetRotation = next
                _deviceRotation.value = tiltDegrees(next)
            }
        }
    }

    private val mainExecutor get() = ContextCompat.getMainExecutor(context)

    override suspend fun requestPermission() {
        val host = activity()
        if (host != null) {
            host.awaitActivityResult(
                keyPrefix = "darkchamber-camera",
                contract = ActivityResultContracts.RequestPermission(),
                input = Manifest.permission.CAMERA,
            )
            asked = true
        }
        refreshPermission()
        // The grant arrives while the screen is already started; the component answers it by
        // calling start, which is idempotent.
    }

    override suspend fun requestMicrophonePermission() {
        val host = activity()
        if (host != null) {
            host.awaitActivityResult(
                keyPrefix = "darkchamber-microphone",
                contract = ActivityResultContracts.RequestPermission(),
                input = Manifest.permission.RECORD_AUDIO,
            )
            microphoneAsked = true
        }
        refreshMicrophonePermission()
    }

    override fun openSettings() {
        val host = activity() ?: return
        host.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", host.packageName, null)),
        )
    }

    override fun start() {
        refreshPermission()
        refreshMicrophonePermission()
        if (started || _permission.value != CameraPermission.Granted) return
        started = true
        owner.moveTo(Lifecycle.State.RESUMED)
        if (tilt.canDetectOrientation()) tilt.enable()
        val ready = provider
        if (ready != null) {
            bind(ready)
            return
        }
        quietCameraX()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val loaded = runCatching { future.get() }.getOrNull()
                if (loaded == null) {
                    _availability.value = CameraAvailability.Unavailable
                    return@addListener
                }
                provider = loaded
                // Stopped while CameraX was starting: bind nothing.
                if (started) bind(loaded)
            },
            mainExecutor,
        )
    }

    override fun stop() {
        if (!started) return
        started = false
        tilt.disable()
        // Abandoned before the camera goes, so that end is the one that stands: releasing the camera
        // makes CameraX finalize the file as if it were whole, which a lock must not read as a save.
        active?.let { recording ->
            active = null
            recording.reported.finish(RecordingEnd.Abandoned)
            recording.platform.close()
        }
        provider?.unbindAll()
        forgetCamera()
        owner.moveTo(Lifecycle.State.CREATED)
    }

    override fun setMode(mode: CaptureMode) {
        if (mode == this.mode || active != null) return
        this.mode = mode
        rebind()
    }

    override fun setQuality(quality: VideoQuality) {
        if (quality == this.quality || active != null) return
        this.quality = quality
        if (mode == CaptureMode.Video) rebind()
    }

    override fun setTorch(on: Boolean) {
        torch = on
        applyTorch()
    }

    override fun record(file: Path, sizeLimitBytes: Long, withAudio: Boolean): Recording {
        val output = videoCapture?.output
        if (camera == null || mode != CaptureMode.Video || output == null || active != null) {
            return ReportedRecording.refused()
        }
        val options = FileOutputOptions.Builder(File(file.toString()))
            .setFileSizeLimit(sizeLimitBytes)
            .build()
        val pending = output.prepareRecording(context, options).withSoundIf(withAudio)
        var recording: ActiveRecording? = null
        val reported = ReportedRecording(onStop = { recording?.platform?.stop() })
        val platform = try {
            pending.start(mainExecutor) { event -> recording?.let { onRecordingEvent(it, event) } }
        } catch (e: IllegalStateException) {
            return ReportedRecording.refused()
        }
        recording = ActiveRecording(reported, platform, File(file.toString()))
        active = recording
        return reported
    }

    override fun setLens(lens: Lens) {
        if (lens == this.lens || lens !in _capabilities.value.lenses || active != null) return
        this.lens = lens
        rebind()
    }

    override fun setFlash(mode: FlashMode) {
        flash = mode
        imageCapture.flashMode = flashModeFor(mode)
    }

    override fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    override fun focusAt(x: Float, y: Float) {
        val view = previewView ?: return
        val control = camera?.cameraControl ?: return
        val point = view.meteringPointFactory.createPoint(x * view.width, y * view.height)
        control.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    override suspend fun capture(): CaptureResult {
        if (camera == null || mode != CaptureMode.Photo) return CaptureResult.Failed
        return suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                // The callback copies the JPEG out of the platform's buffer, a few milliseconds of
                // memcpy; it hands straight on to the save queue, which moves the encrypt off this
                // thread.
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureStarted() {
                        sounds?.play(MediaActionSound.SHUTTER_CLICK)
                    }

                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            // Lock arrived while the platform was capturing: read nothing at all.
                            if (!continuation.isActive) return
                            if (image.format != ImageFormat.JPEG || image.planes.isEmpty()) {
                                continuation.resume(CaptureResult.Failed)
                                return
                            }
                            val photo = CapturedPhoto(copyJpeg(image.planes[0].buffer), image.imageInfo.rotationDegrees)
                            // Cancelled between here and delivery: clear the copy this made.
                            continuation.resume(CaptureResult.Captured(photo)) { _, _, _ -> photo.jpeg.fill(0) }
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (continuation.isActive) continuation.resume(CaptureResult.Failed)
                    }
                },
            )
        }
    }

    /**
     * The viewfinder. `COMPATIBLE` — a `TextureView` drawn into the app's own window — rather than a
     * `SurfaceView` on a surface of its own: the window carries the screenshot flag, and the preview
     * should be inside what that flag covers rather than relying on a child surface inheriting it.
     *
     * `FIT_CENTER`: the preview has the capture's shape, and so does the box the screen draws it in,
     * so nothing is cropped and what is shown is what is saved.
     */
    @Composable
    override fun Preview(modifier: Modifier) {
        AndroidView(
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    preview?.surfaceProvider = surfaceProvider
                    previewView = this
                }
            },
            onRelease = { view ->
                if (previewView === view) {
                    preview?.surfaceProvider = null
                    previewView = null
                }
            },
            modifier = modifier,
        )
    }

    private fun onRecordingEvent(recording: ActiveRecording, event: VideoRecordEvent) {
        val stats = event.recordingStats
        recording.reported.report(recordingProgressFor(stats.numBytesRecorded, stats.recordedDurationNanos))
        when (event) {
            is VideoRecordEvent.Start -> {
                recording.sounded = true
                sounds?.play(MediaActionSound.START_VIDEO_RECORDING)
            }
            is VideoRecordEvent.Finalize -> {
                if (active === recording) active = null
                // Only for a recording that ended on its own or was stopped to be kept: an abandoned
                // one has already reported, and its end is not what CameraX says now. CameraX opens
                // the file on the first encoded data, which can be after the lock deleted it, so an
                // abandoned one's file goes again here.
                val first = recording.reported.finishFromPlatform(recordingEndFor(event.error)) { recording.file.delete() }
                if (first && recording.sounded) {
                    sounds?.play(MediaActionSound.STOP_VIDEO_RECORDING)
                }
            }
        }
    }

    // Sound only when asked for and granted. The grant is checked here, at the call, because the
    // platform's own check is a runtime one and a refusal there would throw.
    private fun PendingRecording.withSoundIf(wanted: Boolean): PendingRecording {
        if (!wanted || !microphoneGranted()) return this
        return try {
            withAudioEnabled()
        } catch (e: SecurityException) {
            this
        }
    }

    private fun microphoneGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refreshPermission() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val rationale = activity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ?: false
        _permission.value = permissionState(granted, asked, rationale)
    }

    private fun refreshMicrophonePermission() {
        val rationale = activity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ?: false
        _microphonePermission.value = microphonePermissionState(microphoneGranted(), microphoneAsked, rationale)
    }

    /**
     * CameraX's own logging down to errors, before its provider first exists. At debug level its
     * recorder writes the output path to the log as each recording starts — our scratch file's name
     * and the vault's directory. A random name says nothing about the clip, but the seam promises the
     * path is never logged, and this code is not the only thing that logs. Once per process: the
     * configuration can be set only before the provider is created, and a second call throws.
     */
    private fun quietCameraX() {
        if (configured) return
        configured = true
        runCatching {
            ProcessCameraProvider.configureInstance(
                CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
                    .setMinimumLoggingLevel(Log.ERROR)
                    .build(),
            )
        }
    }

    private fun rebind() {
        provider?.takeIf { started }?.let(::bind)
    }

    private fun bind(provider: ProcessCameraProvider) {
        provider.unbindAll()
        forgetCamera()
        val lenses = buildSet {
            if (provider.has(CameraSelector.DEFAULT_BACK_CAMERA)) add(Lens.Back)
            if (provider.has(CameraSelector.DEFAULT_FRONT_CAMERA)) add(Lens.Front)
        }
        if (lenses.isEmpty()) {
            _capabilities.value = CameraCapabilities.None
            _availability.value = CameraAvailability.Unavailable
            return
        }
        if (lens !in lenses) lens = lenses.first()
        val selector = lens.selector()
        // The chosen quality is kept even where this lens cannot record it, so flipping back to one
        // that can records it again; the recorder's fallback picks the nearest this lens can do.
        val qualities = qualitiesOf(provider, selector)

        val nextPreview = PreviewUseCase.Builder().setResolutionSelector(resolutionFor(mode)).build()
        nextPreview.surfaceProvider = previewView?.surfaceProvider
        preview = nextPreview
        val capture: UseCase = when (mode) {
            CaptureMode.Photo -> imageCapture.also { videoCapture = null }
            CaptureMode.Video -> buildVideoCapture().also { videoCapture = it }
        }

        val bound = try {
            provider.bindToLifecycle(owner, selector, nextPreview, capture)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
        camera = bound
        if (bound == null) {
            _availability.value = CameraAvailability.Unavailable
            return
        }
        imageCapture.flashMode = flashModeFor(flash)
        applyTorch()
        _availability.value = CameraAvailability.Available
        bound.cameraInfo.cameraState.observe(owner) { state ->
            if (camera === bound) _availability.value = availabilityFor(state.error?.code)
        }
        bound.cameraInfo.zoomState.observe(owner) { zoom ->
            if (camera === bound) {
                _capabilities.value = CameraCapabilities(
                    lenses = lenses,
                    hasFlash = bound.cameraInfo.hasFlashUnit(),
                    minZoom = zoom.minZoomRatio,
                    maxZoom = zoom.maxZoomRatio,
                    videoQualities = qualities,
                )
            }
        }
    }

    // The 16:9 recorder at the chosen quality, falling back to the nearest the lens can do rather
    // than failing to bind. Rotated by the tilt, as the photo is.
    private fun buildVideoCapture(): VideoCapture<Recorder> {
        val wanted = qualityOf(quality)
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(wanted, FallbackStrategy.lowerQualityOrHigherThan(wanted)))
            .setAspectRatio(aspectRatioFor(CaptureMode.Video))
            .build()
        return VideoCapture.Builder(recorder).setTargetRotation(rotation).build()
    }

    // What the lens records, asked of CameraX before binding so the offer is known with the camera.
    private fun qualitiesOf(provider: ProcessCameraProvider, selector: CameraSelector): Set<VideoQuality> {
        val info = runCatching { provider.getCameraInfo(selector) }.getOrNull() ?: return emptySet()
        val supported = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
        return videoQualitiesFor(fullHd = Quality.FHD in supported, ultraHd = Quality.UHD in supported)
    }

    // On only in video and only where there is a light; anything else turns it off.
    private fun applyTorch() {
        val bound = camera ?: return
        if (!bound.cameraInfo.hasFlashUnit()) return
        bound.cameraControl.enableTorch(torch && mode == CaptureMode.Video)
    }

    // The observers above live as long as [owner], which is never destroyed, so they are removed by
    // hand — otherwise every open would add two more to the same camera's state.
    private fun forgetCamera() {
        camera?.cameraInfo?.let { info ->
            info.cameraState.removeObservers(owner)
            info.zoomState.removeObservers(owner)
        }
        camera = null
    }

    private fun ProcessCameraProvider.has(selector: CameraSelector): Boolean =
        runCatching { hasCamera(selector) }.getOrDefault(false)

    private fun Lens.selector(): CameraSelector =
        when (this) {
            Lens.Back -> CameraSelector.DEFAULT_BACK_CAMERA
            Lens.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
        }

    private fun qualityOf(quality: VideoQuality): Quality =
        when (quality) {
            VideoQuality.HD -> Quality.FHD
            VideoQuality.UHD -> Quality.UHD
        }

    private fun resolutionFor(mode: CaptureMode): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(aspectRatioFor(mode), AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .build()

    /** One recording: the caller's handle, CameraX's, and whether its start sound has played. */
    private class ActiveRecording(val reported: ReportedRecording, val platform: PlatformRecording, val file: File) {
        var sounded = false
    }

    /** The lifecycle CameraX is bound to: resumed between [start] and [stop], created otherwise. */
    private class SeamLifecycle : LifecycleOwner {
        private val registry by lazy { LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED } }
        override val lifecycle: Lifecycle get() = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }
}
