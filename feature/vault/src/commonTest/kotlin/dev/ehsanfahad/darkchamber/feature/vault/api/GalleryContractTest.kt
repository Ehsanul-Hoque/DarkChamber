package dev.ehsanfahad.darkchamber.feature.vault.api

import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    /**
     * A name being typed is vault content too, and this one is reachable from two states — the album
     * grid's and an album screen's — so printing either of those would carry it (hard rule #4).
     */
    @Test
    fun a_draft_toString_never_leaks_the_name_being_typed() {
        val draft = AlbumDraft(name = "Divorce papers", problem = AlbumDraftProblem.CreateFailed)

        val rendered = draft.toString()
        assertFalse(rendered.contains("Divorce"), "the typed name must not appear in $rendered")
        assertFalse(
            AlbumsState(draft = draft).toString().contains("Divorce"),
            "nor through the state that holds it",
        )
        assertFalse(
            AlbumState.Present(name = AlbumTileName.Unreadable, rename = draft).toString().contains("Divorce"),
            "nor through an album's own rename state",
        )
        assertTrue(rendered.contains("CreateFailed"), "the problem is not content, and is worth keeping")
    }

    /** The meta on its own is just as loggable as the tile that holds it, so it redacts too. */
    @Test
    fun readable_meta_toString_never_leaks_the_name_or_mime() {
        val rendered = GalleryItemMeta.Readable("passport-scan.pdf", "application/pdf").toString()

        assertFalse(rendered.contains("passport-scan"), "the name must not appear in $rendered")
        assertFalse(rendered.contains("application/pdf"), "the mime must not appear in $rendered")
    }

    /**
     * A selection compares by the ids it holds, not by the identity of the arrays holding them —
     * the same `ByteArray` trap `GalleryItem` avoids one field further in. Nothing on today's paths
     * rebuilds an identical selection, so this is the type's contract rather than a live bug: a
     * caller comparing two selections, or two `GalleryState`s holding them, has to get the answer it
     * expects.
     */
    @Test
    fun two_selections_of_the_same_items_are_equal() {
        val one = GallerySelection(active = true, ids = listOf(byteArrayOf(1), byteArrayOf(2)))
        val two = GallerySelection(active = true, ids = listOf(byteArrayOf(1), byteArrayOf(2)))

        assertEquals(one, two, "distinct arrays, the same selection")
        assertEquals(one.hashCode(), two.hashCode(), "equal values must not land in different buckets")
        assertEquals(
            GalleryState(selection = one),
            GalleryState(selection = two),
            "and the state holding one compares the same way",
        )
    }

    @Test
    fun selections_differing_in_items_or_mode_are_not_equal() {
        val selected = GallerySelection(active = true, ids = listOf(byteArrayOf(1)))

        assertNotEquals(selected, GallerySelection(active = true, ids = listOf(byteArrayOf(2))))
        assertNotEquals(selected, GallerySelection(active = true, ids = listOf(byteArrayOf(1), byteArrayOf(2))))
        assertNotEquals(selected, GallerySelection(active = false, ids = listOf(byteArrayOf(1))), "the mode counts")
    }

    @Test
    fun a_selection_finds_an_id_by_content_and_logs_only_a_count() {
        val selection = GallerySelection(active = true, ids = listOf(byteArrayOf(0xDE.toByte(), 0xAD.toByte())))

        assertTrue(selection.contains(byteArrayOf(0xDE.toByte(), 0xAD.toByte())), "a different array, the same id")
        assertFalse(selection.contains(byteArrayOf(0xDE.toByte())))
        assertEquals(1, selection.count)

        val rendered = selection.toString()
        assertFalse(rendered.lowercase().contains("dead"), "no id bytes in $rendered")
        assertTrue(rendered.contains("count=1"), "a count is fine to log: $rendered")
    }
}
