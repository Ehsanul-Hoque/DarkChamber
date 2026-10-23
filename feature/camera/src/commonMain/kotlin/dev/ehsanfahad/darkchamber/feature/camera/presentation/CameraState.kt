package dev.ehsanfahad.darkchamber.feature.camera.presentation

import dev.ehsanfahad.darkchamber.feature.camera.api.CameraAvailability
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraCapabilities
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraPermission
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.Lens
import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureProblem
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureQueueState
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationInfo
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationTitle
import dev.ehsanfahad.darkchamber.feature.camera.domain.MicrophoneHint
import dev.ehsanfahad.darkchamber.feature.camera.domain.RecorderState
import dev.ehsanfahad.darkchamber.feature.camera.domain.VideoLaneState
import dev.ehsanfahad.darkchamber.feature.camera.domain.recordingSound

/**
 * Everything the camera screen draws from, and every decision about it.
 *
 * The decisions live here as properties rather than in the composable because there are no UI
 * tests: a rule written inside a layout is a rule nothing asserts. The screen reads these and draws.
 *
 * Holds ids, counts and enums only — the newest photo is [CaptureQueueState.lastSavedId], never its
 * bytes. The one piece of decrypted content is the album's name inside [destination], and its
 * `toString` does not print it.
 */
internal class CameraState(
    /** `null` until the destination's first emission. */
    val destination: DestinationInfo?,
    val permission: CameraPermission,
    val availability: CameraAvailability,
    val capabilities: CameraCapabilities,
    val queue: CaptureQueueState,
    /** The lens, flash setting and zoom last asked of the camera — what the controls draw. */
    val lens: Lens = Lens.Back,
    val flash: FlashMode = FlashMode.Off,
    val zoom: Float = 1f,
    /** What the shutter takes, and the video settings last asked of the camera. */
    val mode: CaptureMode = CaptureMode.Photo,
    val quality: VideoQuality = VideoQuality.HD,
    val torch: Boolean = false,
    /** This screen's recording, if any, and the session's video lane. */
    val recorder: RecorderState = RecorderState(),
    val lane: VideoLaneState = VideoLaneState(),
    val microphone: MicrophonePermission = MicrophonePermission.Granted,
    /** Whether this screen has started a recording yet — the microphone hint is said before the first. */
    val hasRecorded: Boolean = false,
) {
    private val albumGone: Boolean get() = destination?.title == DestinationTitle.AlbumGone

    private val cameraUsable: Boolean
        get() = permission == CameraPermission.Granted && availability == CameraAvailability.Available && !albumGone

    /**
     * Whether a press would take a photo. A destination still loading does not block it — the
     * import checks the album itself, so a photo in that first moment is filed or fails honestly.
     * An album known to be gone does: every photo would fail.
     */
    val shutterEnabled: Boolean
        get() = cameraUsable && queue.shutterEnabled

    /** A recording is running — from start until its end has been handled. */
    val recording: Boolean get() = recorder.active != null

    /** The row under the viewfinder: the mode labels, or while recording the status in their place. */
    val row: CameraRow get() = if (recording) CameraRow.Status else CameraRow.ModeLabels

    /**
     * The shutter. In video it records, and while recording it stops — once: a second press while
     * the platform finalizes would do nothing. Record is out of reach while the last clip is still
     * saving, since the lane holds one at a time, and says so ([Shutter.waitingOnSave]); and on a lens
     * that records no video.
     */
    val shutter: Shutter
        get() = when {
            mode == CaptureMode.Photo -> Shutter(ShutterKind.Photo, enabled = shutterEnabled)
            recording -> Shutter(ShutterKind.Stop, enabled = !recorder.stopping)
            else -> Shutter(
                ShutterKind.Record,
                enabled = cameraUsable && capabilities.videoQualities.isNotEmpty() && !lane.busy,
                waitingOnSave = lane.busy,
            )
        }

    /** The viewfinder's shape is the capture's, so it shows exactly what is saved. */
    val captureRatio: CaptureRatio
        get() = when (mode) {
            CaptureMode.Photo -> CaptureRatio.FourByThree
            CaptureMode.Video -> CaptureRatio.SixteenByNine
        }

    /**
     * The light control: the flash in photo, the torch in video — which stays while recording, when
     * light is most often wanted. None on a lens without a light.
     */
    val light: LightControl?
        get() = when {
            !capabilities.hasFlash -> null
            mode == CaptureMode.Photo -> LightControl.Flash(flash)
            else -> LightControl.Torch(torch)
        }

    /**
     * The quality chip, in video only and never while recording. Hidden, not disabled, on a lens
     * that offers one quality: a chip with nothing to switch to is a control that does nothing.
     */
    val qualityChip: QualityChip?
        get() {
            if (mode != CaptureMode.Video || recording) return null
            val next = capabilities.videoQualities.firstOrNull { it != quality } ?: return null
            return QualityChip(current = quality, next = next)
        }

    /** The no-sound mark, beside a recording that has no sound. */
    val showsNoSoundMark: Boolean get() = recorder.active?.withAudio == false

    /**
     * How to turn sound on, said in video before this screen's first recording; after that the
     * no-sound mark says it. `null` when the microphone is already allowed.
     */
    val microphoneHint: MicrophoneHint?
        get() {
            if (mode != CaptureMode.Video || recording || hasRecorded) return null
            return recordingSound(microphone).hint.takeIf { it != MicrophoneHint.None }
        }

    /**
     * What stands in place of the viewfinder, if anything. Permission speaks first: whether a
     * camera is available means nothing until we may open one. Not asked yet shows nothing — the
     * system's own prompt is up.
     */
    val explainer: CameraExplainer
        get() = when (permission) {
            CameraPermission.NotAsked -> CameraExplainer.None
            CameraPermission.Denied -> CameraExplainer.Denied
            CameraPermission.PermanentlyDenied -> CameraExplainer.PermanentlyDenied
            CameraPermission.Granted -> when (availability) {
                CameraAvailability.Available -> CameraExplainer.None
                CameraAvailability.InUseByAnotherApp -> CameraExplainer.InUse
                CameraAvailability.Unavailable -> CameraExplainer.Unavailable
            }
        }

    /**
     * Whether the viewfinder is drawn: exactly when no explainer stands in its place, so an
     * explainer is never read over a live preview.
     */
    val showsViewfinder: Boolean get() = explainer == CameraExplainer.None

    /** The destination's item count, or `null` when it is not known — never a stand-in zero. */
    val count: Long? get() = destination?.count

    /**
     * The one-line message under the viewfinder. An album that is gone outranks a single failed
     * photo, because it explains every photo after it too.
     */
    val message: CameraMessage?
        get() = when {
            albumGone -> CameraMessage.AlbumGone
            queue.problem == CaptureProblem.SaveFailed -> CameraMessage.SaveFailed
            queue.problem == CaptureProblem.CaptureFailed -> CameraMessage.CaptureFailed
            else -> null
        }

    /**
     * A flip control only when there is another lens to flip to — and not while recording, since
     * switching lens would rebind the camera and end the clip.
     */
    val flipOffered: Boolean get() = capabilities.lenses.size > 1 && !recording

    /** A light control only when the lens in use has a light. */
    val flashOffered: Boolean get() = light != null

    override fun toString(): String =
        "CameraState(destination=$destination, permission=$permission, availability=$availability, " +
            "queue=$queue, lens=$lens, flash=$flash, zoom=$zoom, mode=$mode, quality=$quality, " +
            "torch=$torch, recorder=$recorder, lane=$lane, microphone=$microphone)"
}

/** What sits under the viewfinder. */
internal enum class CameraRow { ModeLabels, Status }

/** What the shutter does now. */
internal enum class ShutterKind { Photo, Record, Stop }

/**
 * The shutter as drawn: what it does, whether it can, and — when it cannot record — whether that is
 * because the last clip is still saving, so its description can say why.
 */
internal data class Shutter(val kind: ShutterKind, val enabled: Boolean, val waitingOnSave: Boolean = false)

/** The capture's shape, which the viewfinder takes. */
internal enum class CaptureRatio(val longOverShort: Float) {
    FourByThree(4f / 3f),
    SixteenByNine(16f / 9f),
}

/** The one light control, in whichever form the mode gives it. */
internal sealed interface LightControl {
    data class Flash(val mode: FlashMode) : LightControl

    data class Torch(val on: Boolean) : LightControl
}

/** The quality chip: what is set, and what a tap switches to. */
internal data class QualityChip(val current: VideoQuality, val next: VideoQuality)

/** What replaces the viewfinder when the camera cannot be shown. */
internal enum class CameraExplainer { None, Denied, PermanentlyDenied, InUse, Unavailable }

/** The one-line message the screen shows, most important first. */
internal enum class CameraMessage { AlbumGone, SaveFailed, CaptureFailed }
