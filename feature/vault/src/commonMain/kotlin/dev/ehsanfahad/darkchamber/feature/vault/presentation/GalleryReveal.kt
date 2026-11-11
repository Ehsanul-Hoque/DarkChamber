package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem

/**
 * Where to scroll so the item with [key] is in view after the viewer — or `null` to leave the grid
 * where the user left it: when the item is already among the [visible] tiles, and when it is no longer
 * listed (deleted from the viewer, or gone some other way), since there is nothing to bring into view.
 */
internal fun revealScroll(items: List<GalleryItem>, key: String, visible: IntRange): Int? {
    val index = items.indexOfFirst { it.id.toListKey() == key }
    return if (index < 0 || index in visible) null else index
}
