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
     * The size of the area a picture is drawn in, in pixels. Nothing is decoded until this is known,
     * because the decode is sized from it.
     */
    fun viewportMeasured(widthPx: Int, heightPx: Int)

    /** The pager settled on the page at [index]. */
    fun showPage(index: Int)

    /** Ask to delete the item shown: opens the confirmation. */
    fun requestDelete()

    /** Delete the item shown, for good. Ignored unless the confirmation is open. */
    fun confirmDelete()

    /** Close the confirmation without deleting. Ignored once the delete has started. */
    fun cancelDelete()

    /** Clear a shown [ViewerNotice]. */
    fun dismissNotice()

    /** Leave the viewer, reporting the item last shown. */
    fun back()
}
