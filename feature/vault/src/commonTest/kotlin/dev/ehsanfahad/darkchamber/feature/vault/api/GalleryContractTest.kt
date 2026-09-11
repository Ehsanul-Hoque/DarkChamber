package dev.ehsanfahad.darkchamber.feature.vault.api

import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GalleryContractTest {

    private fun item(id: ByteArray, meta: GalleryItemMeta = GalleryItemMeta.Readable("a.txt", "text/plain")) =
        GalleryItem(id = id, kind = VaultItemKind.FILE, size = 7L, createdAt = 11L, meta = meta)

    /**
     * An opening vault must not flash "no items yet" at someone whose vault is full, so the initial
     * state is loading rather than empty-and-settled.
     */
    @Test
    fun the_initial_state_is_loading_not_empty() {
        val state = GalleryState()

        assertTrue(state.loading, "starts loading until the first catalog emission")
        assertEquals(emptyList(), state.items)
        assertFalse(state.importing)
        assertNull(state.error)
    }

    @Test
    fun items_with_equal_content_are_equal() {
        assertEquals(item(byteArrayOf(1, 2)), item(byteArrayOf(1, 2)), "equal content compares equal")
        assertEquals(
            item(byteArrayOf(1, 2)).hashCode(),
            item(byteArrayOf(1, 2)).hashCode(),
            "equal values share a hash",
        )
        assertTrue(item(byteArrayOf(1, 2)) != item(byteArrayOf(3, 4)), "a different id is a different item")
    }

    @Test
    fun a_damaged_item_differs_from_a_readable_one_with_the_same_id() {
        val id = byteArrayOf(9)

        assertTrue(
            item(id) != item(id, GalleryItemMeta.Unreadable),
            "losing readable metadata must re-render the tile",
        )
    }

    /** Nothing derived from vault content may reach a log or a crash string (hard rule #4). */
    @Test
    fun toString_never_leaks_the_name_or_mime() {
        val rendered = item(byteArrayOf(1, 2), GalleryItemMeta.Readable("passport-scan.pdf", "application/pdf")).toString()

        assertFalse(rendered.contains("passport-scan"), "the name must not appear in $rendered")
        assertFalse(rendered.contains("application/pdf"), "the mime must not appear in $rendered")
    }

    /** The meta on its own is just as loggable as the tile that holds it, so it redacts too. */
    @Test
    fun readable_meta_toString_never_leaks_the_name_or_mime() {
        val rendered = GalleryItemMeta.Readable("passport-scan.pdf", "application/pdf").toString()

        assertFalse(rendered.contains("passport-scan"), "the name must not appear in $rendered")
        assertFalse(rendered.contains("application/pdf"), "the mime must not appear in $rendered")
    }
}
