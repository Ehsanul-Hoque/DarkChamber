package dev.ehsanfahad.darkchamber.feature.camera.presentation

import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationTitle

/**
 * The camera's words, in one place a test can read. Each line is written from the failure path —
 * what is still true when the thing it describes has gone wrong — and the screen picks a line from
 * state rather than choosing one inside a layout.
 *
 * Claims this screen never makes, and the test asserts absent: that capture is silent (iOS forces a
 * shutter sound in some regions), that photos never leave the vault (they will be exportable), that
 * memory is wiped (only this code's own arrays are zeroed), that the disk is full (no error says so),
 * or that a damaged name was tampered with (a failed tag is all that is known).
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

    /** The shutter is a drawing — the aperture — so it needs words to be spoken. */
    fun shutterDescription(): String = "Take photo"

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
    fun lastShotDescription(hasShot: Boolean): String =
        if (hasShot) "Last photo saved" else "No photo taken yet"
}

/** An explainer's sentence, and its one action if there is one that would help. */
internal data class ExplainerCopy(val text: String, val action: String?)
