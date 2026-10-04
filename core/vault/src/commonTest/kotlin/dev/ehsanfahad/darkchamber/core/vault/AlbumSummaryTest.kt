package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * `AlbumSummary`'s value semantics and its log safety — the two things that stop being free the
 * moment a class holds a `ByteArray`.
 *
 * **Equality has teeth here.** The album grid re-emits on every write to the *items* table, which is
 * the cross-table notification the whole listing depends on, so a listing recomputed from unchanged
 * rows is the ordinary case rather than a corner. Identity-based array comparison would make every
 * one of those look like a change, redrawing the grid on each import of an unrelated file.
 */
class AlbumSummaryTest {
    private fun summary(
        id: ByteArray = byteArrayOf(1, 2, 3),
        name: Outcome<String> = Outcome.Ok("Receipts"),
        itemCount: Int = 4,
        coverItemId: ByteArray? = byteArrayOf(9),
        createdAt: Long = 1_700_000_000_000L,
    ) = AlbumSummary(id, name, itemCount, coverItemId, createdAt)

    @Test
    fun two_summaries_over_equal_bytes_are_equal() {
        val a = summary()
        val b = summary(id = byteArrayOf(1, 2, 3), coverItemId = byteArrayOf(9))

        assertEquals(a, b, "distinct arrays with the same contents are the same album")
        assertEquals(a.hashCode(), b.hashCode(), "equal values must not land in different buckets")
    }

    @Test
    fun a_different_id_or_cover_is_a_different_summary() {
        assertNotEquals(summary(), summary(id = byteArrayOf(1, 2, 4)))
        assertNotEquals(summary(), summary(coverItemId = byteArrayOf(8)), "the cover changed, so the tile did")
    }

    @Test
    fun an_absent_cover_and_a_present_one_are_not_equal() {
        val empty = summary(coverItemId = null, itemCount = 0)
        val filled = summary(coverItemId = byteArrayOf(9), itemCount = 0)

        assertNotEquals(empty, filled)
        assertNotEquals(filled, empty)
        assertEquals(empty, summary(coverItemId = null, itemCount = 0), "and two empty albums match")
    }

    /**
     * A count change with every byte otherwise identical is what an import into an album looks like.
     * It has to register, or the grid shows a stale number — worse than showing none, because a
     * number looks authoritative.
     */
    @Test
    fun a_changed_count_is_a_changed_summary() {
        assertNotEquals(summary(itemCount = 4), summary(itemCount = 5))
    }

    /**
     * A damaged album is not equal to a readable one, and two damaged albums over the same row are
     * equal to each other — so a listing that keeps failing to open the same name does not thrash.
     */
    @Test
    fun the_name_outcome_participates_in_equality() {
        val damaged = summary(name = Outcome.Err(VaultError.Crypto.IntegrityFailure))

        assertNotEquals(summary(), damaged)
        assertEquals(damaged, summary(name = Outcome.Err(VaultError.Crypto.IntegrityFailure)))
    }

    @Test
    fun toString_prints_no_name_and_no_id_bytes() {
        val printed = summary(id = byteArrayOf(0xDE.toByte(), 0xAD.toByte()), name = Outcome.Ok("Köln")).toString()

        assertFalse(printed.contains("Köln"), "the name is decrypted content: $printed")
        assertEquals(true, printed.contains("id=2B"), "the id is reported as a size: $printed")
        assertFalse(printed.contains("-34"), "not as its bytes, signed: $printed")
        assertFalse(printed.lowercase().contains("dead"), "and not as hex: $printed")
        assertEquals(true, printed.contains("itemCount=4"), "counts and dates are fine to log: $printed")
    }

    @Test
    fun toString_says_whether_a_cover_exists_without_naming_it() {
        assertEquals(true, summary(coverItemId = byteArrayOf(9)).toString().contains("cover=present"))
        assertEquals(true, summary(coverItemId = null).toString().contains("cover=absent"))
    }
}
