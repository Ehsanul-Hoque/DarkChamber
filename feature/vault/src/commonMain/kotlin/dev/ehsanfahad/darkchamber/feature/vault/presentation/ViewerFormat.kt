package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerKind
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackNote
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerNotice
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import dev.ehsanfahad.darkchamber.feature.vault.api.label
import kotlin.math.roundToInt

/** The viewer's words. Every line is written from the failure path (hard rule 6). */
internal object ViewerFormat {
    /**
     * What a page says in place of a picture — `null` for the picture itself and for the wait.
     *
     * - **No viewer yet.** Nothing was read, so nothing is known about the bytes: it does not call the
     *   file safe or intact, and it does not promise when a viewer arrives.
     * - **Too large / can't show.** The file may be perfectly sound; the limit is this device and this
     *   screen. Neither line may sound like damage.
     * - **Unverified.** A failed integrity check is all that is known. It cannot tell decay from
     *   interference, so it names neither — it says the file may have changed, which both would be —
     *   and that none of it is shown, which is the rule: never render part of an unverified file.
     * - **Stopped unverified.** A clip refused partway through playing: every part that played was a
     *   verified chunk, and playback stopped at the first that was not. So it says playback stopped —
     *   never that none of it was shown, which would be false — and, as above, names no cause.
     * - **Couldn't open.** Why is not known, so no why.
     */
    fun pageMessage(page: ViewerPage): String? =
        when (page) {
            ViewerPage.Loading, ViewerPage.Picture, is ViewerPage.Media -> null
            is ViewerPage.NotViewable -> "This file can't be opened here."
            ViewerPage.TooLarge -> "This file is too large to open here."
            ViewerPage.CantShow -> "This file can't be shown on this device."
            ViewerPage.CantPlay -> "This device can't play this file."
            ViewerPage.Unverified ->
                "This file couldn't be verified, so none of it is shown. It may have changed since it was saved."
            ViewerPage.StoppedUnverified ->
                "This file couldn't be verified from here on, so playback stopped. It may have changed since it was saved."
            ViewerPage.NeedsUpdate -> "This file was saved by a newer version of DarkChamber. Update the app to open it."
            ViewerPage.CouldntOpen -> "This file couldn't be opened."
        }

    /**
     * The note on a page that plays in part. The device is the limit, as on [ViewerPage.CantPlay]: it
     * says what is missing and where the limit is, and nothing about the file's state.
     */
    fun trackNote(note: TrackNote): String =
        when (note) {
            TrackNote.NoPicture -> "Video can't be shown on this device"
            TrackNote.NoSound -> "No sound — this device can't play this audio"
        }

    // ---- the player ------------------------------------------------------------------------------

    /** A time on the clock a player shows: "0:42", "12:05", "1:02:03". */
    fun playTime(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1_000L
        val hours = seconds / 3_600L
        val minutes = seconds / 60L % 60L
        val secs = (seconds % 60L).toString().padStart(2, '0')
        return if (hours > 0L) "$hours:${minutes.toString().padStart(2, '0')}:$secs" else "$minutes:$secs"
    }

    /** Elapsed and total under the seek bar, "0:42 / 1:51"; the elapsed time alone until the total is known. */
    fun playTimes(positionMs: Long, durationMs: Long?): String =
        if (durationMs == null) playTime(positionMs) else "${playTime(positionMs)} / ${playTime(durationMs)}"

    /** What the seek bar says it stands at, to a screen reader: "0:42 of 1:51". */
    fun seekState(positionMs: Long, durationMs: Long?): String =
        if (durationMs == null) playTime(positionMs) else "${playTime(positionMs)} of ${playTime(durationMs)}"

    fun seekLabel(): String = "Position"

    /** A speed as the setting and the hold show it: "0.5×", "1×", "1.25×". */
    fun speed(speed: Float): String {
        val hundredths = (speed * 100f).roundToInt()
        val whole = hundredths / 100
        val part = hundredths % 100
        val written = when {
            part == 0 -> "$whole"
            part % 10 == 0 -> "$whole.${part / 10}"
            else -> "$whole.${part.toString().padStart(2, '0')}"
        }
        return "$written×"
    }

    /** The speed setting, to a screen reader. */
    fun speedDescription(speed: Float): String = "Speed, ${speed(speed)}"

    /** The centre control's words: what pressing it does. */
    fun centreControl(control: CentreControl): String =
        when (control) {
            CentreControl.Play -> "Play"
            CentreControl.Pause -> "Pause"
            CentreControl.Replay -> "Play again"
        }

    /** The orientation button's label: where it turns the screen. */
    fun orientationLabel(target: ScreenOrientation): String =
        if (target == ScreenOrientation.Landscape) "Landscape" else "Portrait"

    /** The orientation button, to a screen reader. */
    fun orientationDescription(target: ScreenOrientation): String =
        "Turn to ${orientationLabel(target).lowercase()}"

    /** What a double tap shows as it moves: "+10 s", "−10 s". */
    fun skipFeedback(forward: Boolean): String {
        val seconds = PlaybackRules.SKIP_MS / 1_000L
        return if (forward) "+$seconds s" else "−$seconds s"
    }

    /** What a player page's picture area is, to a screen reader. */
    fun playerArea(kind: PlayerKind): String = if (kind == PlayerKind.Video) "Video" else "Audio"

    /** The picture's screen-reader action: what activating it does to the controls. */
    fun controlsAction(shown: Boolean): String = if (shown) "Hide controls" else "Show controls"

    /** The screen-reader actions that skip, as its actions menu lists them. */
    fun skipAction(forward: Boolean): String {
        val seconds = PlaybackRules.SKIP_MS / 1_000L
        return if (forward) "Forward $seconds seconds" else "Back $seconds seconds"
    }

    /** A brightness or volume level as a slide shows it: "60%". */
    fun levelFeedback(level: Float): String = "${(level * 100f).roundToInt()}%"

    /** The bar's title: the item's name, or the grid's own words for one that will not open. */
    fun title(meta: GalleryItemMeta): String = meta.label()

    /** "12 of 340" — counted from one, the way a person counts. */
    fun position(index: Int, count: Int): String = "${index + 1} of $count"

    class DetailRow(val label: String, val value: String)

    /**
     * The Details sheet, in order. The plaintext columns (size, when added) are always known; the name,
     * the type and the album's name come from sealed blobs and read as unverifiable when they will not
     * open, in the same words the grid uses.
     *
     * "Size" is the size the user handed over, not what the vault occupies — the container adds a
     * header and a tag per chunk — so the row is never labelled as space or storage. "Added" is the
     * gallery's coarse age on purpose: the exact moment a file entered the vault is not something the
     * screen needs to show.
     */
    fun details(item: GalleryItem, album: AlbumTileName?, now: Long): List<DetailRow> {
        val type = when (val meta = item.meta) {
            is GalleryItemMeta.Readable -> typeName(meta.mime)
            GalleryItemMeta.Unreadable -> meta.label()
        }
        return listOf(
            DetailRow("Name", item.meta.label()),
            DetailRow("Type", type),
            DetailRow("Size", GalleryFormat.size(item.size)),
            DetailRow("Added", GalleryFormat.age(item.createdAt, now)),
            DetailRow("Album", album?.label() ?: "Not in an album"),
        )
    }

    fun deleteQuestion(count: Int): String = if (count == 1) "Delete this file?" else "Delete $count files?"

    /** The confirming button says the scale, as "Delete N originals" does on the import summary. */
    fun deleteAction(count: Int): String = if (count == 1) "Delete" else "Delete $count"

    /**
     * Permanent, and why. Deleting removes the item's row, and its key with it, so the app can never
     * decrypt the file again — that is what the code guarantees, and all this says. It does not say
     * the bytes are erased or that nobody could ever recover anything: flash storage can keep old
     * blocks, and `docs/THREAT_MODEL.md` says so (hard rule 6).
     */
    fun deleteExplanation(count: Int): String =
        if (count == 1) {
            "It will be removed from the vault for good. Its key is destroyed, so DarkChamber can never open it again."
        } else {
            "They will be removed from the vault for good. Their keys are destroyed, so DarkChamber can never open them again."
        }

    fun deleted(count: Long): String =
        when (count) {
            0L -> "Nothing was deleted."
            1L -> "1 file deleted."
            else -> "$count files deleted."
        }

    /** The catalog deletes all or nothing, so this is true; why it failed is not known, so no why. */
    fun deleteFailed(): String = "Couldn't delete. Nothing was deleted."

    /** What the viewer says after a delete from it — always one file. */
    fun notice(notice: ViewerNotice): String =
        when (notice) {
            ViewerNotice.Deleted -> deleted(1)
            ViewerNotice.DeleteFailed -> deleteFailed()
        }

    /** The mark drawn above a page's message. */
    fun pageBadge(page: ViewerPage, meta: GalleryItemMeta): String =
        when {
            page is ViewerPage.NotViewable -> page.badge
            page == ViewerPage.Unverified || page == ViewerPage.StoppedUnverified -> WARNING_BADGE
            meta is GalleryItemMeta.Readable -> GalleryFormat.typeBadge(meta.mime)
            else -> WARNING_BADGE
        }

    fun cancel(): String = "Cancel"

    /** The confirm button once pressed: the only thing saying why the buttons stopped responding. */
    fun deleting(): String = "Deleting…"

    /** The grid's mark for something that could not be verified. */
    private const val WARNING_BADGE = "!"

    fun back(): String = "Back"

    fun detailsAction(): String = "Details"

    // A family and a format: "image/jpeg" → "Image · JPEG". A type that says nothing more specific
    // than "some bytes" is called a file and left there, rather than dressed up as a format.
    private fun typeName(mime: String): String {
        if (mime == "application/pdf") return "PDF"
        val family = when (mime.substringBefore('/')) {
            "image" -> "Image"
            "video" -> "Video"
            "audio" -> "Audio"
            "text" -> "Text"
            else -> "File"
        }
        val format = mime.substringAfter('/', missingDelimiterValue = "").substringBefore(';').trim()
        return if (format.isEmpty() || format == "octet-stream") family else "$family · ${format.uppercase()}"
    }

    /** Only a failed integrity check takes the warning style (DESIGN: danger is for integrity failures). */
    fun pageIsWarning(page: ViewerPage): Boolean = page == ViewerPage.Unverified || page == ViewerPage.StoppedUnverified
}
