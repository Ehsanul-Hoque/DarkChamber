package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.FormatNote
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
     * - **A PDF's password / unreadable.** Both were read and verified, so neither may sound like
     *   damage. A password is the document's own lock, and there is nowhere here to type it. A renderer
     *   that will not read a verified file is the renderer's limit: the line says the file is unchanged
     *   since it was saved — the vault checked exactly that — and names no cause it was not told.
     * - **Not text.** Read and verified, so not damage: the file is unchanged. Why it is not text — another
     *   encoding, or not text at all — is not known, so it names neither.
     * - **Couldn't open.** Why is not known, so no why.
     */
    fun pageMessage(page: ViewerPage): String? =
        when (page) {
            ViewerPage.Loading, ViewerPage.Picture, is ViewerPage.Media, is ViewerPage.Pdf, is ViewerPage.Text -> {
                null
            }

            ViewerPage.NotText -> {
                "This file can't be shown as text here. It is unchanged since it was saved."
            }

            is ViewerPage.NotViewable -> {
                "This file can't be opened here."
            }

            ViewerPage.TooLarge -> {
                "This file is too large to open here."
            }

            ViewerPage.CantShow -> {
                "This file can't be shown on this device."
            }

            ViewerPage.CantPlay -> {
                "This device can't play this file."
            }

            ViewerPage.PasswordProtected -> {
                "This PDF is locked with a password, which can't be entered here."
            }

            ViewerPage.Unreadable -> {
                "This PDF can't be read here. It is unchanged since it was saved."
            }

            ViewerPage.Unverified -> {
                "This file couldn't be verified, so none of it is shown. It may have changed since it was saved."
            }

            ViewerPage.StoppedUnverified -> {
                "This file couldn't be verified from here on, so playback stopped. It may have changed since it was saved."
            }

            ViewerPage.NeedsUpdate -> {
                "This file was saved by a newer version of DarkChamber. Update the app to open it."
            }

            ViewerPage.CouldntOpen -> {
                "This file couldn't be opened."
            }
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
    fun playTimes(
        positionMs: Long,
        durationMs: Long?,
    ): String = if (durationMs == null) playTime(positionMs) else "${playTime(positionMs)} / ${playTime(durationMs)}"

    /** What the seek bar says it stands at, to a screen reader: "0:42 of 1:51". */
    fun seekState(
        positionMs: Long,
        durationMs: Long?,
    ): String = if (durationMs == null) playTime(positionMs) else "${playTime(positionMs)} of ${playTime(durationMs)}"

    fun seekLabel(): String = "Position"

    /** A speed as the setting and the hold show it: "0.5×", "1×", "1.25×". */
    fun speed(speed: Float): String {
        val hundredths = (speed * 100f).roundToInt()
        val whole = hundredths / 100
        val part = hundredths % 100
        val written =
            when {
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
    fun orientationDescription(target: ScreenOrientation): String = "Turn to ${orientationLabel(target).lowercase()}"

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
    fun position(
        index: Int,
        count: Int,
    ): String = "${index + 1} of $count"

    class DetailRow(
        val label: String,
        val value: String,
    )

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
    fun details(
        item: GalleryItem,
        album: AlbumTileName?,
        now: Long,
    ): List<DetailRow> {
        val type =
            when (val meta = item.meta) {
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

    /** What the viewer says after a delete from it — always one file — or a link no app took. */
    fun notice(notice: ViewerNotice): String =
        when (notice) {
            ViewerNotice.Deleted -> deleted(1)
            ViewerNotice.DeleteFailed -> deleteFailed()
            ViewerNotice.LinkNotOpened -> linkNotOpened()
        }

    /** The mark drawn above a page's message. */
    fun pageBadge(
        page: ViewerPage,
        meta: GalleryItemMeta,
    ): String =
        when {
            page is ViewerPage.NotViewable -> page.badge
            page == ViewerPage.Unverified || page == ViewerPage.StoppedUnverified -> WARNING_BADGE
            meta is GalleryItemMeta.Readable -> GalleryFormat.typeBadge(meta)
            else -> WARNING_BADGE
        }

    /** A PDF's page, counted from 1: the indicator's words and each page's spoken name. */
    fun pdfPage(
        index: Int,
        count: Int,
    ): String = "Page ${index + 1} of $count"

    /**
     * The page indicator's place in the bottom bar for [page], or `null` for no place — anything but a
     * PDF, where Details and Delete share the bar. Every PDF has the place, so Details and Delete sit in
     * the same spot on all of them, open or not:
     *
     * - Open, it names [reading], the page most on screen, and a tap jumps.
     * - Reading, the place is held empty for the first [PDF_DASHES_AFTER_MS] of [loadingMs] — a small
     *   PDF opens, and a locked one is refused, before dashes would show, so nothing flashes — then
     *   shows dashes, with no numbers and no jump.
     * - Refused, before its read or after it, the place stays empty; the page says why.
     *
     * At a large font it wraps, and only as "Page N" over "of M": the spaces inside each half do not
     * break. What a screen reader hears has plain spaces.
     */
    fun pdfIndicator(
        page: ViewerPage,
        meta: GalleryItemMeta,
        reading: Int,
        loadingMs: Long,
    ): IndicatorSlot? =
        when {
            !isPdf(meta) -> {
                null
            }

            page is ViewerPage.Pdf -> {
                IndicatorSlot.Shown(
                    PageIndicator(
                        text = "Page$NBSP${reading + 1} of$NBSP${page.pageCount}",
                        spoken = "${pdfPage(reading, page.pageCount)}, jump to a page",
                        ready = true,
                    ),
                )
            }

            page != ViewerPage.Loading -> {
                IndicatorSlot.Held
            }

            loadingMs < PDF_DASHES_AFTER_MS -> {
                IndicatorSlot.Held
            }

            else -> {
                IndicatorSlot.Shown(PageIndicator("Page$NBSP– of$NBSP–", "Pages loading", ready = false))
            }
        }

    /** How long a PDF reads before its indicator shows dashes; a quicker one goes straight to its pages. */
    const val PDF_DASHES_AFTER_MS = 300L

    /** Beside the fast-scroll thumb while it is dragged: the page the drag has reached, counted from 1. */
    fun fastScrollPage(index: Int): String = "Page ${index + 1}"

    /** Beside a text file's fast-scroll thumb while it is dragged: the line the drag has reached, counted from 1. */
    fun fastScrollLine(index: Int): String = "Line ${index + 1}"

    /**
     * The Formatted / Plain switch: its words name the view shown ([formatted] or as written), as the
     * page indicator names the page shown; a screen reader hears what a tap does as its action.
     */
    fun textViewName(formatted: Boolean): String = if (formatted) "Formatted" else "Plain"

    fun textViewAction(formatted: Boolean): String = if (formatted) "Show as written" else "Show formatted"

    /**
     * Above a Markdown or JSON file shown only as written. Each says why there is no formatted view and
     * nothing about the file's state: it was read and verified, and the limit is this viewer's.
     */
    fun formatNote(note: FormatNote): String =
        when (note) {
            FormatNote.TooLarge -> "Shown as written: too large to format here."
            FormatNote.TooComplex -> "Shown as written: too complex to format here."
            FormatNote.NotJson -> "Shown as written: not valid JSON, so it can't be formatted."
        }

    /** What a screen reader says for a task list item's box: drawn as a mark, never a control. */
    fun taskSpoken(done: Boolean): String = if (done) "Done" else "Not done"

    /**
     * An image in a Markdown file, which is never fetched: its description where the image would be, in
     * brackets so it does not read as the file's own words; an image with no description still shows
     * its place.
     */
    fun imageText(description: String): String = if (description.isBlank()) "[Image]" else "[Image: $description]"

    /** The jump-to-page dialog's title, and its confirm button. */
    fun jumpTitle(): String = "Go to page"

    fun jumpGo(): String = "Go"

    /** The jump field's label: which numbers it takes. */
    fun jumpFieldLabel(count: Int): String = "Page, 1 to $count"

    /**
     * Why a jump did not go, shown in the dialog — `null` when it goes. A number no page has is said
     * to be out of the document's range, never moved to the nearest page.
     */
    fun jumpProblem(
        jump: PageJump,
        count: Int,
    ): String? =
        when (jump) {
            is PageJump.Go -> null
            PageJump.Empty -> "Type a page number."
            PageJump.NotANumber -> "Use digits only."
            PageJump.OutOfRange -> "This PDF has pages 1 to $count."
        }

    /** In place of one page of a PDF that would not draw; the others still show. Names no cause. */
    fun pdfPageNotShown(): String = "This page couldn't be shown."

    fun cancel(): String = "Cancel"

    /*
     * The confirmation before a web link leaves the vault. It shows the address exactly as the file
     * gives it — never the link's words, which can say anything — and the site it reaches on a line of
     * its own, since a user part (`google.com@…`) can make an address read as another. Every line is
     * true on the failure path: the browser and the site are not ours, so they "may" and "can"; the
     * vault locks only once the browser has taken the address.
     */

    fun linkTitle(): String = "Open this link in your browser?"

    fun linkSite(host: String): String = "Site: $host"

    fun linkLeaves(): String =
        "The address leaves the vault. Your browser may keep it in its history and sync it to your other devices, " +
            "and the site can learn that it was opened."

    fun linkLocks(): String = "Opening it locks the vault."

    fun linkOpen(): String = "Open"

    /**
     * No app took the address: nothing left the vault and nothing locked. Why is not known — no app
     * for web links, or the platform refusing this address — so it names only what happened.
     */
    fun linkNotOpened(): String = "No app opened this link."

    /** The confirm button once pressed: the only thing saying why the buttons stopped responding. */
    fun deleting(): String = "Deleting…"

    /** The grid's mark for something that could not be verified. */
    private const val WARNING_BADGE = "!"

    /** A space a line never breaks at (U+00A0), written as its code so no editor shows it as a space. */
    private val NBSP = Char(0x00A0)

    fun back(): String = "Back"

    fun detailsAction(): String = "Details"

    // A family and a format: "image/jpeg" → "Image · JPEG". A type that says nothing more specific
    // than "some bytes" is called a file and left there, rather than dressed up as a format.
    private fun typeName(mime: String): String {
        if (mime == "application/pdf") return "PDF"
        val family =
            when (mime.substringBefore('/')) {
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

/** The PDF page indicator: what it shows, what a screen reader says, and whether it is ready to jump. */
internal data class PageIndicator(
    val text: String,
    val spoken: String,
    val ready: Boolean,
)

/** The indicator's place in the bottom bar: kept empty, or showing an indicator. */
internal sealed interface IndicatorSlot {
    /** The place is kept, with nothing in it and nothing a screen reader lands on: Details and Delete stay put. */
    data object Held : IndicatorSlot

    data class Shown(
        val indicator: PageIndicator,
    ) : IndicatorSlot
}
