package dev.ehsanfahad.darkchamber.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ItemFilterTest {

    /**
     * The reason [ItemFilter.Album] writes its own `equals` instead of being a `data class`:
     * `ByteArray` compares by identity, so the generated one would call two filters over the same
     * album different. Callers compare filters — a listing re-subscribes when its filter changes —
     * and under identity every rebuild of the same filter would read as a change, re-running a
     * query (and in the gallery, re-decrypting every row) for nothing.
     */
    @Test
    fun an_album_filter_is_equal_to_another_over_the_same_id() {
        val id = byteArrayOf(0xA1.toByte(), 0xB2.toByte())
        val a = ItemFilter.Album(id)
        val b = ItemFilter.Album(byteArrayOf(0xA1.toByte(), 0xB2.toByte()))

        assertTrue(a.id !== b.id, "two distinct arrays, so identity would say unequal")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode(), "equal values must hash together to be usable as keys")
    }

    @Test
    fun album_filters_over_different_ids_are_not_equal() {
        assertNotEquals(ItemFilter.Album(byteArrayOf(1)), ItemFilter.Album(byteArrayOf(2)))
        assertNotEquals<ItemFilter>(ItemFilter.Album(byteArrayOf(1)), ItemFilter.All)
        assertNotEquals<ItemFilter>(ItemFilter.All, ItemFilter.Unfiled, "the two id-less cases are distinct")
    }

    /** `toString` carries no album name — only a secret-free shape (hard rule 4). */
    @Test
    fun an_album_filter_prints_no_identifier() {
        val printed = ItemFilter.Album(byteArrayOf(0xDE.toByte(), 0xAD.toByte())).toString()
        assertTrue("de" !in printed.lowercase(), "the id is not in the text: $printed")
    }
}
