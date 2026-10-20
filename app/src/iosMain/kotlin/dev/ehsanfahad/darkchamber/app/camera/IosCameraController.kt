package dev.ehsanfahad.darkchamber.app.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureResult
import dev.ehsanfahad.darkchamber.feature.camera.api.CapturedPhoto
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceRotationCoordinator
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureExposureModeAutoExpose
import platform.AVFoundation.AVCaptureFlashMode
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureFocusModeAutoFocus
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionInterruptionReasonKey
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.AVFoundation.exposureMode
import platform.AVFoundation.exposurePointOfInterest
import platform.AVFoundation.fileDataRepresentation
import platform.AVFoundation.focusMode
import platform.AVFoundation.focusPointOfInterest
import platform.AVFoundation.hasFlash
import platform.AVFoundation.isExposurePointOfInterestSupported
import platform.AVFoundation.isFocusPointOfInterestSupported
import platform.AVFoundation.maxAvailableVideoZoomFactor
import platform.AVFoundation.minAvailableVideoZoomFactor
import platform.AVFoundation.position
import platform.AVFoundation.requestAccessForMediaType
import platform.AVFoundation.videoZoomFactor
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * AVFoundation as the [CameraController] seam.
 *
 * **A capture never touches a file.** The photo comes back from `AVCapturePhotoOutput` as an
 * `AVCapturePhoto` and `fileDataRepresentation()` — the encoded JPEG, in memory — is copied into a
 * `ByteArray`; no file URL is ever involved (hard rule #1). No location is attached: the settings
 * carry no metadata of ours, and the app holds no location permission.
 *
 * **Orientation travels in the photo's own EXIF**, set from the system's rotation coordinator at the
 * moment of capture, so the rotation reported to the save path is always 0 and the orientation
 * patch leaves the bytes as they are.
 *
 * Main-thread confined like the seam, except `startRunning` / `stopRunning`, which block and run on
 * a serial queue of their own as Apple asks.
 *
 * **Unverified on a device** — there is no iPhone for this cycle. The simulator has no camera, so
 * there this reports unavailable and nothing below the device lookup runs.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCameraController : CameraController {

    private val _permission = MutableStateFlow(currentPermission())
    override val permission: StateFlow<CameraPermission> = _permission.asStateFlow()

    private val _availability = MutableStateFlow(CameraAvailability.Available)
    override val availability: StateFlow<CameraAvailability> = _availability.asStateFlow()

    private val _capabilities = MutableStateFlow(CameraCapabilities.None)
    override val capabilities: StateFlow<CameraCapabilities> = _capabilities.asStateFlow()

    private val sessionQueue = dispatch_queue_create("dev.ehsanfahad.darkchamber.camera", null)
    private val session = AVCaptureSession()
    private val photoOutput = AVCapturePhotoOutput()
    private val previewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    private var input: AVCaptureDeviceInput? = null
    private var rotation: AVCaptureDeviceRotationCoordinator? = null
    private var lens = Lens.Back
    private var flash = FlashMode.Off
    private var started = false
    private var configured = false
    private var observers: List<NSObjectProtocol> = emptyList()

    // The weak-delegate hazard the pickers met first: the photo output does not retain its delegate,
    // and a collected one means the capture never answers.
    private val captures = mutableSetOf<PhotoDelegate>()

    override suspend fun requestPermission() {
        suspendCancellableCoroutine { continuation ->
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ ->
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        _permission.value = currentPermission()
    }

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    override fun start() {
        _permission.value = currentPermission()
        if (started || _permission.value != CameraPermission.Granted) return
        started = true
        if (!configured) configure()
        if (input == null) return
        observe()
        dispatch_async(sessionQueue) { session.startRunning() }
    }

    override fun stop() {
        if (!started) return
        started = false
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers = emptyList()
        dispatch_async(sessionQueue) { session.stopRunning() }
    }

    override fun setLens(lens: Lens) {
        if (lens == this.lens || lens !in _capabilities.value.lenses) return
        this.lens = lens
        session.beginConfiguration()
        input?.let(session::removeInput)
        input = null
        attach(lens)
        session.commitConfiguration()
    }

    override fun setFlash(mode: FlashMode) {
        flash = mode
    }

    override fun setZoom(ratio: Float) {
        val device = input?.device ?: return
        if (!device.lockForConfiguration(null)) return
        device.videoZoomFactor = ratio.toDouble()
            .coerceIn(device.minAvailableVideoZoomFactor, device.maxAvailableVideoZoomFactor)
        device.unlockForConfiguration()
    }

    override fun focusAt(x: Float, y: Float) {
        val device = input?.device ?: return
        val (width, height) = previewLayer.bounds.useContents { size.width to size.height }
        val point = previewLayer.captureDevicePointOfInterestForPoint(CGPointMake(x * width, y * height))
        if (!device.lockForConfiguration(null)) return
        if (device.isFocusPointOfInterestSupported()) {
            device.focusPointOfInterest = point
            device.focusMode = AVCaptureFocusModeAutoFocus
        }
        if (device.isExposurePointOfInterestSupported()) {
            device.exposurePointOfInterest = point
            device.exposureMode = AVCaptureExposureModeAutoExpose
        }
        device.unlockForConfiguration()
    }

    override suspend fun capture(): CaptureResult {
        if (input == null || !started) return CaptureResult.Failed
        val settings = AVCapturePhotoSettings.photoSettings()
        val wanted = flash.toAv()
        if (photoOutput.supportedFlashModes.any { (it as? NSNumber)?.longValue == wanted }) settings.flashMode = wanted
        rotation?.videoRotationAngleForHorizonLevelCapture?.let { angle ->
            photoOutput.connectionWithMediaType(AVMediaTypeVideo)?.let { connection ->
                if (connection.isVideoRotationAngleSupported(angle)) connection.videoRotationAngle = angle
            }
        }
        return suspendCancellableCoroutine { continuation ->
            lateinit var delegate: PhotoDelegate
            delegate = PhotoDelegate { jpeg ->
                captures.remove(delegate)
                when {
                    // Lock arrived while the photo was being taken: drop the copy, report nothing.
                    !continuation.isActive -> jpeg?.fill(0)
                    jpeg == null -> continuation.resume(CaptureResult.Failed)
                    else -> continuation.resume(CaptureResult.Captured(CapturedPhoto(jpeg, rotationDegrees = 0))) { _, _, _ ->
                        jpeg.fill(0)
                    }
                }
            }
            captures += delegate
            photoOutput.capturePhotoWithSettings(settings, delegate = delegate)
        }
    }

    @Composable
    override fun Preview(modifier: Modifier) {
        UIKitView(
            factory = { PreviewHost(previewLayer) },
            modifier = modifier,
        )
    }

    private fun currentPermission(): CameraPermission =
        permissionFor(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo))

    private fun configure() {
        configured = true
        session.beginConfiguration()
        session.sessionPreset = AVCaptureSessionPresetPhoto
        if (session.canAddOutput(photoOutput)) session.addOutput(photoOutput)
        attach(lens)
        session.commitConfiguration()
    }

    // Inside a begin/commit pair. Falls back to whichever lens exists; none at all is unavailable.
    private fun attach(wanted: Lens) {
        val lenses = buildSet {
            if (device(Lens.Back) != null) add(Lens.Back)
            if (device(Lens.Front) != null) add(Lens.Front)
        }
        val chosen = if (wanted in lenses) wanted else lenses.firstOrNull()
        val device = chosen?.let(::device)
        val added = device
            ?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, error = null) }
            ?.takeIf { session.canAddInput(it) }
            ?.also { session.addInput(it) }
        input = added
        if (chosen == null || device == null || added == null) {
            _capabilities.value = CameraCapabilities.None
            _availability.value = CameraAvailability.Unavailable
            return
        }
        lens = chosen
        rotation = AVCaptureDeviceRotationCoordinator(device = device, previewLayer = previewLayer)
        _capabilities.value = CameraCapabilities(
            lenses = lenses,
            hasFlash = device.hasFlash,
            minZoom = device.minAvailableVideoZoomFactor.toFloat(),
            maxZoom = device.maxAvailableVideoZoomFactor.toFloat(),
        )
        _availability.value = CameraAvailability.Available
    }

    private fun device(lens: Lens): AVCaptureDevice? =
        AVCaptureDevice.defaultDeviceWithDeviceType(
            AVCaptureDeviceTypeBuiltInWideAngleCamera,
            AVMediaTypeVideo,
            if (lens == Lens.Back) AVCaptureDevicePositionBack else AVCaptureDevicePositionFront,
        )

    private fun observe() {
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        observers = listOf(
            center.addObserverForName(AVCaptureSessionWasInterruptedNotification, session, main) { note: NSNotification? ->
                val reason = (note?.userInfo?.get(AVCaptureSessionInterruptionReasonKey) as? NSNumber)?.longValue
                _availability.value = availabilityForInterruption(reason ?: -1L)
            },
            center.addObserverForName(AVCaptureSessionInterruptionEndedNotification, session, main) { _ ->
                _availability.value = availabilityForInterruption(null)
            },
            center.addObserverForName(AVCaptureSessionRuntimeErrorNotification, session, main) { _ ->
                _availability.value = CameraAvailability.Unavailable
            },
        )
    }

    private fun FlashMode.toAv(): AVCaptureFlashMode =
        when (this) {
            FlashMode.Off -> AVCaptureFlashModeOff
            FlashMode.Auto -> AVCaptureFlashModeAuto
            FlashMode.On -> AVCaptureFlashModeOn
        }
}

/** Hands one capture's encoded bytes back, copied out of the system's buffer — or `null`. */
@OptIn(ExperimentalForeignApi::class)
private class PhotoDelegate(private val done: (ByteArray?) -> Unit) : NSObject(), AVCapturePhotoCaptureDelegateProtocol {
    @ObjCSignatureOverride
    override fun captureOutput(output: AVCapturePhotoOutput, didFinishProcessingPhoto: AVCapturePhoto, error: NSError?) {
        val data = if (error == null) didFinishProcessingPhoto.fileDataRepresentation() else null
        val length = data?.length?.toInt() ?: 0
        if (data == null || length == 0) {
            done(null)
            return
        }
        val bytes = ByteArray(length)
        bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        done(bytes)
    }
}

/** A view whose only content is the preview layer, kept the size of the view. */
@OptIn(ExperimentalForeignApi::class)
private class PreviewHost(private val preview: AVCaptureVideoPreviewLayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        layer.addSublayer(preview)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // No implicit animation: the layer would otherwise glide to its new size on every rotation.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        preview.frame = bounds
        CATransaction.commit()
    }
}
