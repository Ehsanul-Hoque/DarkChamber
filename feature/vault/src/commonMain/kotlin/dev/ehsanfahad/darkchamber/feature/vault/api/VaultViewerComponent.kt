package dev.ehsanfahad.darkchamber.feature.vault.api

import androidx.compose.ui.graphics.ImageBitmap
import com.arkivanov.decompose.value.Value

/**
 * The viewer: one item at a time, swiping through the listing it was opened from — in that listing's
 * order and narrowed by its search, exactly as the grid showed it.
 *
 * Like the gallery and the album grid, the component is the state holder and lives inside the unlocked
 * subtree, so a lock destroys it and everything it holds. **Leaving is navigation**, so it goes out
 * through the callback the host supplied, carrying the id of the item last shown so the grid can bring
 * it into view.
 */
interface VaultViewerComponent {
    val state: Value<ViewerState>

    /**
     * Decoded pictures, keyed by the item's id in lower-case hex — **only for the page shown and the
     * one either side**. A page that leaves that window loses its picture at once, and every one goes
     * when the component is destroyed. Held apart from [state] on purpose: screen state carries ids
     * and descriptions, never content.
     */
    val pictures: Value<Map<String, ImageBitmap>>

    /**
     * The player of the page shown, keyed like [pictures] — **at most one**, and only while a video or
     * audio page is the one on screen. Leaving that page pauses it, remembers where it was and lets it
     * go; coming back opens a new one there. Held apart from [state] for the same reason as [pictures].
     */
    val players: Value<Map<String, VaultPlayer>>

    /**
     * The drawn pages of the PDF shown, by page index (from 0) — **only while a PDF is the page shown,
     * and only the pages near the screen** (see [pdfViewport]). `null` for a page that could not be
     * drawn; a page not drawn yet is absent. Held apart from [state] for the same reason as [pictures].
     */
    val pdfPages: Value<Map<Int, ImageBitmap?>>

    /**
     * What the PDF shown has on screen: the pages [visible], shown [shownWidthPx] wide at [zoom], on a
     * screen [screenWidthPx] wide. The pages near them are drawn to fit; the rest are let go.
     */
    fun pdfViewport(
        visible: IntRange,
        shownWidthPx: Int,
        zoom: Float,
        screenWidthPx: Int,
    )

    /**
     * The text of the text file shown, keyed like [pictures] — **at most one, and only while a text
     * page is the one on screen**: a text file is read when it is shown and let go when it is left.
     * Held apart from [state] for the same reason as [pictures].
     */
    val texts: Value<Map<String, TextDocument>>

    /** Switch Markdown and JSON files between formatted and as written ([ViewerState.formatted]). */
    fun toggleFormatted()

    /** The PDF shown was scrolled to [page], [offsetPx] down it — where reading resumes if it is left and come back to. */
    fun pdfScrolled(
        page: Int,
        offsetPx: Int,
    )

    /**
     * The size of the area a picture is drawn in, in pixels. Nothing is decoded until this is known,
     * because the decode is sized from it.
     */
    fun viewportMeasured(
        widthPx: Int,
        heightPx: Int,
    )

    /** The pager settled on the page at [index]. */
    fun showPage(index: Int)

    /**
     * Set the playing speed to [speed], one of the setting's choices — it applies now and to every item
     * played after, until the viewer closes. Anything the setting does not offer is ignored.
     */
    fun chooseSpeed(speed: Float)

    /** The centre control: pauses a playing item, plays a paused one, and starts a finished one again. */
    fun playPause()

    /** Moves the item shown to [positionMs], kept between its start and its end. */
    fun seekTo(positionMs: Long)

    /** Moves the item shown ten seconds, [forward] or back, kept between its start and its end. */
    fun skip(forward: Boolean)

    /** Plays at double speed ([faster]) or half speed until [releaseSpeed]. */
    fun holdSpeed(faster: Boolean)

    /** Back to the speed set, once a hold ends. */
    fun releaseSpeed()

    /** Sets the volume the slide controls, from 0 to 1 (see [VaultPlayer.setVolume]). */
    fun setVolume(level: Float)

    /** The volume now, where a slide starts from; `null` while nothing plays. */
    fun volume(): Float?

    /** The screen's brightness now, where a slide starts from; `null` where it cannot be changed. */
    fun brightness(): Float?

    /** Sets the screen's brightness, from 0 to 1, while the video page is shown. Ignored on any other page. */
    fun setBrightness(level: Float)

    /** Turns a video page to the orientation [ViewerState.orientationTarget] names. */
    fun switchOrientation()

    /** Ask to delete the item shown: opens the confirmation. */
    fun requestDelete()

    /** Delete the item shown, for good. Ignored unless the confirmation is open. */
    fun confirmDelete()

    /** Close the confirmation without deleting. Ignored once the delete has started. */
    fun cancelDelete()

    /**
     * The reader confirmed opening a link to [address]. Only a web address the link rules accept is
     * handed to the platform's browser; once it has taken the address the vault locks, and when
     * nothing took it nothing locks and [ViewerNotice.LinkNotOpened] says so. Anything else is ignored.
     */
    fun openLink(address: String)

    /** Clear a shown [ViewerNotice]. */
    fun dismissNotice()

    /** Leave the viewer, reporting the item last shown. */
    fun back()
}
