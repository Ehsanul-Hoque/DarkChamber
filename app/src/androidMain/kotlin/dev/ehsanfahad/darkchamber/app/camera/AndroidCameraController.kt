package dev.ehsanfahad.darkchamber.app.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.media.MediaActionSound
import android.net.Uri
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview as PreviewUseCase
import androidx.camera.lifecycle.ProcessCameraProvider
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
import dev.ehsanfahad.darkchamber.feature.camera.api.ReportedRecording
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path
import kotlin.coroutines.resume

/**
 * CameraX as the [CameraController] seam.
 *
 * **A capture never touches a file.** `takePicture` is called with an in-memory callback, the JPEG
 * plane is copied into a `ByteArray` and the `ImageProxy` closed; the `OutputFileOptions` overload is
 * never used — CameraX's saver stages through a file even when handed a stream (hard rule #1). No
 * location is ever set on a capture, and the app holds no location permission.
 *
 * **A shutter sound only where the law asks for one.** CameraX plays none itself; where the device's
 * region requires one (`CameraInfo.mustPlayShutterSound`), the system's shutter click is played as
 * each capture starts. Everywhere else a capture makes no sound of its own.
 *
 * **Bound to a lifecycle of its own**, driven by [start] and [stop], rather than to the activity.
 * The camera screen survives a rotation (the session is retained while the activity is recreated),
 * so binding to "the current activity" would bind to one that is being destroyed, and between the
 * new activity's start and resume there is no current one to find. The screen's own lifecycle is
 * what decides when the camera is open, and that is what calls these two.
 *
 * Main-thread confined, like the seam: every call comes from the screen, and CameraX expects its
 * configuration calls on the main thread.
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

    // Video is not wired to CameraX yet: no microphone is asked for, the tilt is not reported, and
    // [record] refuses — so a recording that cannot happen says so rather than pretending.
    override val microphonePermission: StateFlow<MicrophonePermission> = MutableStateFlow(MicrophonePermission.NotAsked)
    override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)

    private val owner = SeamLifecycle()

    // Built on first use rather than here: constructing a use case reaches into the framework, and
    // this object is built by the composition root at startup, camera or no camera.
    private val preview by lazy { PreviewUseCase.Builder().build() }
    private val imageCapture by lazy {
        ImageCapture.Builder()
            // A private camera is used for a quick shot; latency matters more than the last bit of
            // quality, and the photo is re-encoded by nobody.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private var lens = Lens.Back
    private var flash = FlashMode.Off
    private var started = false

    // Loaded once, on the first capture, and only where a shutter sound is required.
    private val shutterSound: MediaActionSound? by lazy {
        if (CameraInfo.mustPlayShutterSound()) MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } else null
    }

    // Whether this process has raised the system prompt — see [permissionState]. Memory only.
    private var asked = false

    // The phone's tilt, so a photo taken sideways is tagged sideways even if the screen did not turn.
    private val tilt by lazy {
        object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                surfaceRotation(orientation)?.let { imageCapture.targetRotation = it }
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

    override suspend fun requestMicrophonePermission() = Unit

    override fun openSettings() {
        val host = activity() ?: return
        host.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", host.packageName, null)),
        )
    }

    override fun start() {
        refreshPermission()
        if (started || _permission.value != CameraPermission.Granted) return
        started = true
        owner.moveTo(Lifecycle.State.RESUMED)
        if (tilt.canDetectOrientation()) tilt.enable()
        val ready = provider
        if (ready != null) {
            bind(ready)
            return
        }
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
        provider?.unbindAll()
        forgetCamera()
        owner.moveTo(Lifecycle.State.CREATED)
    }

    override fun setMode(mode: CaptureMode) = Unit

    override fun setQuality(quality: VideoQuality) = Unit

    override fun setTorch(on: Boolean) = Unit

    override fun record(file: Path, sizeLimitBytes: Long, withAudio: Boolean): Recording = ReportedRecording.refused()

    override fun setLens(lens: Lens) {
        if (lens == this.lens || lens !in _capabilities.value.lenses) return
        this.lens = lens
        provider?.takeIf { started }?.let(::bind)
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
        if (camera == null) return CaptureResult.Failed
        return suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                // The callback copies the JPEG out of the platform's buffer, a few milliseconds of
                // memcpy; it hands straight on to the save queue, which moves the encrypt off this
                // thread.
                mainExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureStarted() {
                        shutterSound?.play(MediaActionSound.SHUTTER_CLICK)
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
     */
    @Composable
    override fun Preview(modifier: Modifier) {
        AndroidView(
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    preview.surfaceProvider = surfaceProvider
                    previewView = this
                }
            },
            onRelease = { view ->
                if (previewView === view) {
                    preview.surfaceProvider = null
                    previewView = null
                }
            },
            modifier = modifier,
        )
    }

    private fun refreshPermission() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val rationale = activity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ?: false
        _permission.value = permissionState(granted, asked, rationale)
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
        val bound = try {
            provider.bindToLifecycle(owner, lens.selector(), preview, imageCapture)
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
                )
            }
        }
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

    /** The lifecycle CameraX is bound to: resumed between [start] and [stop], created otherwise. */
    private class SeamLifecycle : LifecycleOwner {
        private val registry by lazy { LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED } }
        override val lifecycle: Lifecycle get() = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }
}
