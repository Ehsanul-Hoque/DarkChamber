package dev.ehsanfahad.darkchamber.app.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
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
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingProgress
import dev.ehsanfahad.darkchamber.feature.camera.api.ReportedRecording
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoContainer
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceRotationCoordinator
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureExposureModeAutoExpose
import platform.AVFoundation.AVCaptureFileOutput
import platform.AVFoundation.AVCaptureFileOutputRecordingDelegateProtocol
import platform.AVFoundation.AVCaptureFlashMode
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureFocusModeAutoFocus
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionInterruptionEndedNotification
import platform.AVFoundation.AVCaptureSessionInterruptionReasonKey
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureSessionPreset3840x2160
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureSessionRuntimeErrorNotification
import platform.AVFoundation.AVCaptureSessionWasInterruptedNotification
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVErrorRecordingSuccessfullyFinishedKey
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.AVFoundation.exposureMode
import platform.AVFoundation.exposurePointOfInterest
import platform.AVFoundation.fileDataRepresentation
import platform.AVFoundation.focusMode
import platform.AVFoundation.focusPointOfInterest
import platform.AVFoundation.hasFlash
import platform.AVFoundation.hasTorch
import platform.AVFoundation.isExposurePointOfInterestSupported
import platform.AVFoundation.isFocusPointOfInterestSupported
import platform.AVFoundation.isTorchModeSupported
import platform.AVFoundation.maxAvailableVideoZoomFactor
import platform.AVFoundation.minAvailableVideoZoomFactor
import platform.AVFoundation.position
import platform.AVFoundation.requestAccessForMediaType
import platform.AVFoundation.setTorchMode
import platform.AVFoundation.videoZoomFactor
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * AVFoundation as the [CameraController] seam.
 *
 * **A photo never touches a file.** The photo comes back from `AVCapturePhotoOutput` as an
 * `AVCapturePhoto` and `fileDataRepresentation()` — the encoded JPEG, in memory — is copied into a
 * `ByteArray`; no file URL is ever involved (hard rule #1). No location is attached: the settings
 * carry no metadata of ours, and the app holds no location permission.
 *
 * **A video goes only to the file it is handed.** `AVCaptureMovieFileOutput` records to the caller's
 * scratch URL in the vault's `tmp/`, stopping itself at the caller's size limit
 * (`maxRecordedFileSize`). It was chosen over `AVAssetWriter` because it writes the file, finalizes
 * it and reports how it ended by itself; a writer would be hand-fed every sample buffer, a far larger
 * surface to get right without a device to try it on. Sound is recorded only when the caller asks
 * for it **and** the microphone is granted. The microphone joins the session only for a recording
 * with sound and leaves when it ends, so the system's microphone indicator is lit only while sound
 * is being recorded, as on Android.
 *
 * **One mode at a time.** Photo attaches the photo output at the photo preset (4:3); video attaches
 * the movie output at the 1080p or 2160p preset (16:9). The preview letterboxes (`resizeAspect`), so
 * it shows the frame that is saved.
 *
 * **Orientation travels in the file**, set from the system's rotation coordinator at the moment of
 * capture — in a photo's EXIF, in a clip's track transform — so no rotation is reported to the save
 * path. The same coordinator's reading is the tilt the screen turns its icons by.
 *
 * Main-thread confined like the seam, except `startRunning` / `stopRunning`, which block and run on
 * a serial queue of their own as Apple asks. Every delegate hands its answer to the main queue.
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

    private val _microphonePermission = MutableStateFlow(currentMicrophonePermission())
    override val microphonePermission: StateFlow<MicrophonePermission> = _microphonePermission.asStateFlow()

    // On iOS the movie file output records only to a QuickTime movie file; it has no MP4 option.
    override val videoContainer: VideoContainer = VideoContainer.QuickTime

    private val _deviceRotation = MutableStateFlow(0)
    override val deviceRotation: StateFlow<Int> = _deviceRotation.asStateFlow()

    private val sessionQueue = dispatch_queue_create("dev.ehsanfahad.darkchamber.camera", null)
    private val session = AVCaptureSession()
    private val photoOutput = AVCapturePhotoOutput()
    private val movieOutput = AVCaptureMovieFileOutput()
    private val previewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspect
    }

    private var input: AVCaptureDeviceInput? = null
    private var audioInput: AVCaptureDeviceInput? = null
    private var rotation: AVCaptureDeviceRotationCoordinator? = null
    private var lens = Lens.Back
    private var flash = FlashMode.Off
    private var mode = CaptureMode.Photo
    private var quality = VideoQuality.HD
    private var torch = false
    private var started = false
    private var configured = false
    private var observers: List<NSObjectProtocol> = emptyList()

    // While the camera is open: the tilt, and the running recording's size and length.
    private var ticker: NSTimer? = null

    // The weak-delegate hazard the pickers met first: neither output retains its delegate, and a
    // collected one means the capture or the recording never answers.
    private val captures = mutableSetOf<PhotoDelegate>()
    private val recordings = mutableSetOf<MovieDelegate>()
    private var active: ReportedRecording? = null

    override suspend fun requestPermission() {
        suspendCancellableCoroutine { continuation ->
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ ->
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        _permission.value = currentPermission()
    }

    override suspend fun requestMicrophonePermission() {
        suspendCancellableCoroutine { continuation ->
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeAudio) { _ ->
                // The answer arrives on a queue of the system's; the state is main-confined.
                dispatch_async(dispatch_get_main_queue()) { if (continuation.isActive) continuation.resume(Unit) }
            }
        }
        _microphonePermission.value = currentMicrophonePermission()
    }

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    override fun start() {
        _permission.value = currentPermission()
        _microphonePermission.value = currentMicrophonePermission()
        if (started || _permission.value != CameraPermission.Granted) return
        started = true
        if (!configured) configure()
        if (input == null) return
        observe()
        ticker = NSTimer.scheduledTimerWithTimeInterval(TICK_SECONDS, repeats = true) { _ -> tick() }
        dispatch_async(sessionQueue) { session.startRunning() }
        applyTorch()
    }

    override fun stop() {
        if (!started) return
        started = false
        ticker?.invalidate()
        ticker = null
        // Abandoned before the session stops, so that end is the one that stands: the movie output
        // still finishes the file as the session goes, which a lock must not read as a save.
        active?.let { recording ->
            active = null
            recording.finish(RecordingEnd.Abandoned)
            if (movieOutput.recording) movieOutput.stopRecording()
        }
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers = emptyList()
        dispatch_async(sessionQueue) { session.stopRunning() }
    }

    override fun setMode(mode: CaptureMode) {
        if (mode == this.mode || active != null) return
        this.mode = mode
        if (configured) reconfigure { applyOutputs() }
        applyTorch()
    }

    override fun setQuality(quality: VideoQuality) {
        if (quality == this.quality || active != null) return
        this.quality = quality
        if (configured && mode == CaptureMode.Video) reconfigure { applyPreset() }
    }

    override fun setTorch(on: Boolean) {
        torch = on
        applyTorch()
    }

    override fun record(file: Path, sizeLimitBytes: Long, withAudio: Boolean): Recording {
        if (!started || input == null || mode != CaptureMode.Video || active != null || movieOutput.recording) {
            return ReportedRecording.refused()
        }
        movieOutput.maxRecordedFileSize = sizeLimitBytes
        if (withAudio && _microphonePermission.value == MicrophonePermission.Granted) {
            reconfigure { attachAudio() }
        } else if (audioInput != null) {
            reconfigure { detachAudio() }
        }
        rotation?.videoRotationAngleForHorizonLevelCapture?.let { angle ->
            movieOutput.connectionWithMediaType(AVMediaTypeVideo)?.let { connection ->
                if (connection.isVideoRotationAngleSupported(angle)) connection.videoRotationAngle = angle
            }
        }

        val reported = ReportedRecording(onStop = { if (movieOutput.recording) movieOutput.stopRecording() })
        val url = NSURL.fileURLWithPath(file.toString())
        lateinit var delegate: MovieDelegate
        delegate = MovieDelegate { errorCode, finishedSuccessfully ->
            recordings.remove(delegate)
            if (active === reported) active = null
            if (active == null && audioInput != null) reconfigure { detachAudio() }
            // The movie output starts asynchronously and finishes the file as the session stops, both
            // possibly after the lock deleted it, so an abandoned recording's file goes again here.
            reported.finishFromPlatform(recordingEndFor(errorCode, finishedSuccessfully)) {
                NSFileManager.defaultManager.removeItemAtURL(url, error = null)
            }
        }
        recordings += delegate
        active = reported
        movieOutput.startRecordingToOutputFileURL(url, recordingDelegate = delegate)
        return reported
    }

    override fun setLens(lens: Lens) {
        if (lens == this.lens || lens !in _capabilities.value.lenses || active != null) return
        this.lens = lens
        reconfigure {
            input?.let(session::removeInput)
            input = null
            attach(lens)
            applyPreset()
        }
        applyTorch()
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
        if (input == null || !started || mode != CaptureMode.Photo) return CaptureResult.Failed
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
                    else -> continuation.resume(CaptureResult.Captured(CapturedPhoto(jpeg, rotationDegrees = null))) { _, _, _ ->
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

    private fun currentMicrophonePermission(): MicrophonePermission =
        microphonePermissionFor(AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeAudio))

    private fun configure() {
        configured = true
        reconfigure {
            attach(lens)
            applyOutputs()
        }
    }

    private inline fun reconfigure(change: () -> Unit) {
        session.beginConfiguration()
        change()
        session.commitConfiguration()
    }

    // Inside a configuration. The mode's output in and the other out, then the mode's preset.
    private fun applyOutputs() {
        when (mode) {
            CaptureMode.Photo -> {
                removeOutput(movieOutput)
                detachAudio()
                if (session.canAddOutput(photoOutput)) session.addOutput(photoOutput)
            }
            CaptureMode.Video -> {
                removeOutput(photoOutput)
                if (session.canAddOutput(movieOutput)) session.addOutput(movieOutput)
            }
        }
        applyPreset()
    }

    // Inside a configuration. Video falls back from the chosen quality to the nearest the lens can
    // record, keeping the choice for a lens that can.
    private fun applyPreset() {
        val candidates = when (mode) {
            CaptureMode.Photo -> listOf(AVCaptureSessionPresetPhoto)
            CaptureMode.Video -> when (quality) {
                VideoQuality.UHD -> listOf(AVCaptureSessionPreset3840x2160, AVCaptureSessionPreset1920x1080, AVCaptureSessionPresetHigh)
                VideoQuality.HD -> listOf(AVCaptureSessionPreset1920x1080, AVCaptureSessionPresetHigh)
            }
        }
        candidates.firstOrNull { session.canSetSessionPreset(it) }?.let { session.sessionPreset = it }
    }

    private fun removeOutput(output: AVCaptureOutput) {
        if (session.outputs.contains(output)) session.removeOutput(output)
    }

    // Inside a configuration. Only for a recording with sound, and only while the microphone is
    // allowed; the recording's end takes it out again.
    private fun attachAudio() {
        if (audioInput != null || _microphonePermission.value != MicrophonePermission.Granted) return
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeAudio) ?: return
        val added = AVCaptureDeviceInput.deviceInputWithDevice(device, error = null)
            ?.takeIf { session.canAddInput(it) }
            ?.also { session.addInput(it) }
        audioInput = added
    }

    private fun detachAudio() {
        audioInput?.let(session::removeInput)
        audioInput = null
    }

    // Inside a configuration. Falls back to whichever lens exists; none at all is unavailable.
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
            videoQualities = videoQualitiesFor(
                fullHd = device.supportsAVCaptureSessionPreset(AVCaptureSessionPreset1920x1080),
                ultraHd = device.supportsAVCaptureSessionPreset(AVCaptureSessionPreset3840x2160),
            ),
        )
        _availability.value = CameraAvailability.Available
    }

    // On only in video and only where there is a light; anything else turns it off.
    private fun applyTorch() {
        val device = input?.device ?: return
        if (!device.hasTorch()) return
        val wanted = if (torch && mode == CaptureMode.Video) AVCaptureTorchModeOn else AVCaptureTorchModeOff
        if (!device.isTorchModeSupported(wanted) || !device.lockForConfiguration(null)) return
        device.setTorchMode(wanted)
        device.unlockForConfiguration()
    }

    private fun tick() {
        rotation?.videoRotationAngleForHorizonLevelCapture?.let { _deviceRotation.value = tiltForCaptureAngle(it) }
        active?.report(RecordingProgress(movieOutput.recordedFileSize, recordedDuration()))
    }

    // Zero, not an error, while the output has no length to give yet.
    private fun recordedDuration(): Duration {
        val seconds = CMTimeGetSeconds(movieOutput.recordedDuration)
        return if (seconds.isFinite() && seconds > 0) seconds.seconds else Duration.ZERO
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

    private companion object {
        // Four readings a second: fast enough that the icons follow a turn, and the status line
        // moves smoothly enough for a timer that shows whole seconds.
        const val TICK_SECONDS = 0.25
    }
}

/** Hands one capture's encoded bytes back, copied out of the system's buffer — or `null`. */
@OptIn(ExperimentalForeignApi::class)
// The photo output calls back on a queue of its own, not the main one, so [done] — which touches the
// controller's main-confined state — is handed to the main queue. The copy is made here, while the
// photo's data is certain to be alive.
private class PhotoDelegate(private val done: (ByteArray?) -> Unit) : NSObject(), AVCapturePhotoCaptureDelegateProtocol {
    @ObjCSignatureOverride
    override fun captureOutput(output: AVCapturePhotoOutput, didFinishProcessingPhoto: AVCapturePhoto, error: NSError?) {
        val data = if (error == null) didFinishProcessingPhoto.fileDataRepresentation() else null
        val length = data?.length?.toInt() ?: 0
        val bytes = if (data == null || length == 0) {
            null
        } else {
            ByteArray(length).also { copy -> copy.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
        }
        dispatch_async(dispatch_get_main_queue()) { done(bytes) }
    }
}

/**
 * Hands one recording's finish back: the error code (`null` for none) and whether AVFoundation says
 * the file still holds a finished clip. Never the URL or the error's message, which name the path.
 * Handed to the main queue, like the photo's.
 */
private class MovieDelegate(
    private val done: (errorCode: Long?, finishedSuccessfully: Boolean?) -> Unit,
) : NSObject(), AVCaptureFileOutputRecordingDelegateProtocol {
    @ObjCSignatureOverride
    override fun captureOutput(
        output: AVCaptureFileOutput,
        didFinishRecordingToOutputFileAtURL: NSURL,
        fromConnections: List<*>,
        error: NSError?,
    ) {
        val code = error?.code
        val finished = (error?.userInfo?.get(AVErrorRecordingSuccessfullyFinishedKey) as? NSNumber)?.boolValue
        dispatch_async(dispatch_get_main_queue()) { done(code, finished) }
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
