package dev.ehsanfahad.darkchamber.feature.vault.api

/**
 * What the viewer is showing.
 *
 * [items] is the listing in the order the grid showed it; [index] is the page shown. [pages] says, for
 * each item in the decode window, what its page is — keyed like [VaultViewerComponent.pictures].
 * Descriptions only: no bytes and no pictures live here, and a source rule keeps it that way (this
 * file may mention no picture type).
 */
data class ViewerState(
    val items: List<GalleryItem> = emptyList(),
    val index: Int = 0,
    val pages: Map<String, ViewerPage> = emptyMap(),
    /** True until the listing has arrived and the first page is known. */
    val loading: Boolean = true,
    val delete: ViewerDeleteStage? = null,
    val notice: ViewerNotice? = null,
)

/** Where a delete from the viewer stands. */
enum class ViewerDeleteStage {
    /** The confirmation is open. */
    Asking,

    /** Confirmed and under way; it cannot be called back. */
    Deleting,
}

/** What a finished delete reports. */
enum class ViewerNotice {
    /** The item is gone; its key was destroyed. */
    Deleted,

    /** The vault refused. Nothing was deleted. */
    DeleteFailed,
}
