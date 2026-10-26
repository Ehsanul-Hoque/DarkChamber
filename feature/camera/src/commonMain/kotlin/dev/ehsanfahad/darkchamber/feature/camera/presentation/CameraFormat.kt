package dev.ehsanfahad.darkchamber.feature.camera.presentation

import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingProgress
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationTitle
import dev.ehsanfahad.darkchamber.feature.camera.domain.MicrophoneHint
import dev.ehsanfahad.darkchamber.feature.camera.domain.RecorderNotice
import dev.ehsanfahad.darkchamber.feature.camera.domain.VideoNotice
import dev.ehsanfahad.darkchamber.feature.camera.domain.roomLeft
import kotlin.time.Duration

/**
 * The camera's words, in one place a test can read. Each line is written from the failure path —
 * what is still true when the thing it describes has gone wrong — and the screen picks a line from
 * state rather than choosing one inside a layout.
 *
 * Claims this screen never makes, and the test asserts absent: that capture is silent (iOS forces a
 * shutter sound in some regions), that photos or videos never leave the vault (they will be
 * exportable), that a video is never stored unencrypted (it passes through one plaintext file in
 * app-private storage), that memory is wiped (only this code's own arrays are zeroed), or that a
 * damaged name was tampered with (a failed tag is all that is known). Space is named only where the
 * code knows it is the cause: the vault reported the disk full, the room check refused, or the
 * platform stopped at our limit.
 */
internal object CameraFormat {

    /**
     * The bar's title. [unreadableLabel] is the app's one sentence for a name that will not open,
     * handed in by the host so it is never written a second time here. `null` when there is nothing
     * true to name — an album that has left the vault, or a listing that could not be read.
     */
    fun title(title: DestinationTitle, unreadableLabel: String): String? =
        when (title) {
            DestinationTitle.AllItems -> "All items"
            DestinationTitle.Unfiled -> "Unfiled"
            is DestinationTitle.Album -> title.name
            DestinationTitle.UnreadableAlbum -> unreadableLabel
            DestinationTitle.AlbumGone, DestinationTitle.Unknown -> null
        }

    /**
     * The count chip — the same words an album tile uses under its name, so one number reads one way
     * everywhere. `null` when the count is not known: nothing is better than a zero that is not true.
     */
    fun count(count: Long?): String? =
        when (count) {
            null -> null
            0L -> "Empty"
            1L -> "1 item"
            else -> "$count items"
        }

    /**
     * The message line. A photo whose save was in flight when its album went may not have been
     * saved, so only photos **already saved** are promised — and they are where an album's items go
     * when it is deleted.
     */
    fun message(message: CameraMessage): String =
        when (message) {
            CameraMessage.AlbumGone ->
                "This album isn't in your vault any more. Photos already saved are still there, under Unfiled."
            CameraMessage.OutOfSpace -> OUT_OF_SPACE
            CameraMessage.SaveFailed -> "Couldn't save that photo."
            CameraMessage.CaptureFailed -> "Couldn't take that photo."
        }

    /**
     * What stands in for the viewfinder, and the one action that would help — or none, where no
     * action would. Permanently denied offers Settings rather than "Allow", which would do nothing,
     * and says that going there locks the vault, because it does and the user will come back to a
     * lock screen.
     */
    fun explainer(explainer: CameraExplainer): ExplainerCopy? =
        when (explainer) {
            CameraExplainer.None -> null
            CameraExplainer.Denied ->
                ExplainerCopy("DarkChamber needs the camera to take photos.", action = "Allow")
            CameraExplainer.PermanentlyDenied ->
                ExplainerCopy(
                    "Camera access is turned off for DarkChamber. You can turn it on in Settings — the vault locks while you're there.",
                    action = "Open settings",
                )
            CameraExplainer.InUse -> ExplainerCopy("Camera is in use by another app.", action = null)
            CameraExplainer.Unavailable -> ExplainerCopy("Camera isn't available right now.", action = null)
        }

    /**
     * The shutter is a drawing — the aperture — so it needs words to be spoken. They say what a press
     * does; the platform adds "disabled" itself. Only a record held back by a clip still saving says
     * why, because only then is the reason something the person would otherwise wait on blind.
     */
    fun shutterDescription(shutter: Shutter): String =
        when (shutter.kind) {
            ShutterKind.Photo -> "Take photo"
            ShutterKind.Stop -> "Stop recording"
            ShutterKind.Record ->
                if (!shutter.enabled && shutter.waitingOnSave) "Start recording — unavailable until the last video has saved"
                else "Start recording"
        }

    /** A clock: `mm:ss`, and `h:mm:ss` once a clip passes an hour. Whole seconds, counted up. */
    fun timer(elapsed: Duration): String {
        val total = elapsed.inWholeSeconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "$hours:${two(minutes)}:${two(seconds)}" else "${two(minutes)}:${two(seconds)}"
    }

    /**
     * A byte count in decimal units, as the phone's own storage screens use: whole megabytes below a
     * gigabyte, tenths of a gigabyte above. Rounded **down**, so "left" never claims room that is not
     * there and reads zero at the auto-stop.
     */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < GB) return "${b / MB} MB"
        val tenths = b / (GB / 10)
        return "${tenths / 10}.${tenths % 10} GB"
    }

    /**
     * The status line while recording: how long, how much is recorded, and how much is left **of this
     * clip** — measured against the limit fixed at start, not the disk's free space, so it reaches
     * zero exactly when the recording stops itself. Bytes are labelled as recorded, never as the
     * vault's size or the disk's use.
     */
    fun recordingStatus(progress: RecordingProgress, limitBytes: Long): String =
        "${timer(progress.duration)} · ${size(progress.bytesRecorded)} recorded · " +
            "${size(roomLeft(limitBytes, progress.bytesRecorded))} left"

    /**
     * How to record with sound, said in video before the first recording. Settings, once the system
     * will not ask again, with the same warning as the camera's: going there locks the vault.
     */
    fun microphoneHint(hint: MicrophoneHint): ExplainerCopy? =
        when (hint) {
            MicrophoneHint.None -> null
            MicrophoneHint.Ask -> ExplainerCopy("Videos record without sound until you allow the microphone.", action = "Allow")
            MicrophoneHint.Settings -> ExplainerCopy(
                "Microphone access is turned off, so videos record without sound. You can turn it on in Settings — the vault locks while you're there.",
                action = "Open settings",
            )
        }

    /**
     * What happened to the last recording. Space is named only where the code knows it: the room
     * check refused, or the platform stopped at our limit. Unknown free space is not called too
     * little — nobody measured it.
     */
    fun recorderNotice(notice: RecorderNotice): String =
        when (notice) {
            RecorderNotice.NotEnoughSpace -> "Not enough space to record."
            RecorderNotice.SpaceUnknown -> "Couldn't check the space available, so recording didn't start."
            RecorderNotice.TooShort -> "Too short to save."
            RecorderNotice.StoppedAtLimit -> "Recording stopped — not enough space for a longer clip."
            RecorderNotice.RecordingFailed -> "Couldn't record that video."
        }

    /** What happened to the last clip's save. A full disk is named only when the vault reported one. */
    fun videoNotice(notice: VideoNotice): String =
        when (notice) {
            VideoNotice.OutOfSpace -> OUT_OF_SPACE
            VideoNotice.SaveFailed -> "Couldn't save that video."
            VideoNotice.SavedUnfiled -> "Saved to Unfiled — its album was deleted."
        }

    fun modeLabel(mode: CaptureMode): String =
        when (mode) {
            CaptureMode.Photo -> "Photo"
            CaptureMode.Video -> "Video"
        }

    /** As the flash: the state, which a tap changes. */
    fun torchDescription(on: Boolean): String = if (on) "Torch: on" else "Torch: off"

    fun qualityLabel(quality: VideoQuality): String =
        when (quality) {
            VideoQuality.HD -> "HD"
            VideoQuality.UHD -> "4K"
        }

    fun qualityDescription(quality: VideoQuality): String = "Video quality: ${qualityLabel(quality)}"

    /** The no-sound mark is a drawing; this is what it says. */
    fun noSoundDescription(): String = "Recording without sound"

    /**
     * The microphone toggle, as the flash: the state, which a tap changes. It says what the next
     * recording will get, so a refused microphone reads "off" whatever the toggle was set to.
     */
    fun soundDescription(toggle: MicrophoneToggle): String = if (toggle.soundOn) "Sound: on" else "Sound: off"

    fun flipDescription(): String = "Switch camera"

    /** Names the state, not the action: the control cycles, and what is spoken is what will happen. */
    fun flashDescription(mode: FlashMode): String =
        when (mode) {
            FlashMode.Off -> "Flash: off"
            FlashMode.Auto -> "Flash: auto"
            FlashMode.On -> "Flash: on"
        }

    fun closeDescription(): String = "Close camera"

    /** The last-shot tile does nothing when touched, so it is described, not labelled as a button. */
    fun lastShotDescription(lastShot: LastShot?): String =
        when (lastShot?.kind) {
            null -> "Nothing taken yet"
            CaptureMode.Photo -> "Last photo saved"
            CaptureMode.Video -> "Last video saved"
        }

    /** The tile's ring, spoken: a percentage only when there is a size to measure it against. */
    fun savingDescription(progress: Float?): String =
        if (progress == null) "Saving video" else "Saving video, ${(progress * 100).toInt()}%"

    /** The notice line, in each source's own words — one wording per event, wherever it is shown. */
    fun notice(notice: CameraNotice): String =
        when (notice) {
            is CameraNotice.Photo -> message(notice.message)
            is CameraNotice.Recording -> recorderNotice(notice.notice)
            is CameraNotice.Saving -> videoNotice(notice.notice)
        }

    // The one line that names a full disk, for photos and video alike — reached only from the
    // vault's own Storage.Full.
    private const val OUT_OF_SPACE = "Couldn't save — your phone is out of space."
    private const val MB = 1_000_000L
    private const val GB = 1_000_000_000L

    private fun two(n: Long): String = n.toString().padStart(2, '0')
}

/** An explainer's sentence, and its one action if there is one that would help. */
internal data class ExplainerCopy(val text: String, val action: String?)
