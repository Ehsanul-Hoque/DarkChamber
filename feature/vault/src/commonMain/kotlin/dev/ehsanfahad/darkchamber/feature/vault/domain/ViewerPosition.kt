package dev.ehsanfahad.darkchamber.feature.vault.domain

/**
 * Where the viewer's pager stands in the listing it browses: a page showing one item, or nothing
 * left to show. Holds an item's **id** — never its content — like every screen state in the vault.
 */
internal sealed interface ViewerPosition {
    /** Equal by the id's content and the index: the pager scrolls by index, so a moved item is a change. */
    class Showing(val id: ByteArray, val index: Int) : ViewerPosition {
        override fun equals(other: Any?): Boolean =
            other is Showing && other.index == index && other.id.contentEquals(id)

        override fun hashCode(): Int = 31 * id.contentHashCode() + index

        override fun toString(): String = "Showing(index=$index)"
    }

    data object Close : ViewerPosition
}

/**
 * Where this page stands after the listing under it changed. **It follows its item, not its
 * index:** while the item is still listed, the page is wherever it now is. If it is gone (deleted),
 * the page shows the item that took its place — the next one along — or, when it was the last, the
 * one now last. An empty listing closes the viewer.
 */
internal fun ViewerPosition.Showing.following(listing: List<ByteArray>): ViewerPosition {
    if (listing.isEmpty()) return ViewerPosition.Close
    val stillThere = listing.indexOfFirst { it.contentEquals(id) }
    val now = if (stillThere >= 0) stillThere else minOf(index, listing.lastIndex)
    return ViewerPosition.Showing(listing[now], now)
}

/**
 * The first page: the item that was tapped. If it is already gone by the first listing (shredded in
 * between), the viewer closes — opening on a neighbour would show something nobody asked for.
 */
internal fun startPosition(startId: ByteArray, listing: List<ByteArray>): ViewerPosition {
    val index = listing.indexOfFirst { it.contentEquals(startId) }
    return if (index < 0) ViewerPosition.Close else ViewerPosition.Showing(listing[index], index)
}
