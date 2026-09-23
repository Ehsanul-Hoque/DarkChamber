package dev.ehsanfahad.darkchamber.feature.vault.presentation

import coil3.fetch.SourceFetchResult
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge between the image loader and the encrypted store.
 *
 * Three outcomes have to stay distinct, and only two of them look the same on screen: a preview,
 * *no* preview (the ordinary state for most of a vault), and a preview whose bytes failed their
 * integrity tag — which is a tampering signal and must not be quietly rendered as "this item never
 * had one".
 */
class VaultThumbnailFetcherTest {
    private val id = ByteArray(16) { it.toByte() }
    private val thumbnail = ByteArray(64) { (it * 3).toByte() }

    @Test
    fun an_item_with_a_thumbnail_is_fetched_as_its_bytes() = runTest {
        val items = FakeVaultItems(thumbnails = mapOf(id.toList() to Outcome.Ok(thumbnail)))

        val result = fetcher(items).fetch()

        val source = assertIs<SourceFetchResult>(result)
        assertContentEquals(thumbnail, source.source.source().readByteArray())
        assertEquals(1, items.thumbnailCalls)
    }

    @Test
    fun an_item_with_no_thumbnail_fetches_nothing_rather_than_failing() = runTest {
        val items = FakeVaultItems(thumbnails = mapOf(id.toList() to Outcome.Ok(null)))

        // Most of a vault is in this state — every non-image, everything imported before previews
        // existed. The tile falls back to its type badge; nothing here is an error.
        assertNull(fetcher(items).fetch())
    }

    @Test
    fun a_damaged_thumbnail_fails_instead_of_looking_like_an_absent_one() = runTest {
        val items = FakeVaultItems(
            thumbnails = mapOf(id.toList() to Outcome.Err(VaultError.Crypto.IntegrityFailure)),
        )

        val thrown = assertFailsWith<VaultThumbnailException> { fetcher(items).fetch() }

        assertEquals(VaultError.Crypto.IntegrityFailure, thrown.error)
    }

    @Test
    fun the_failure_carries_no_decrypted_content() {
        val thrown = VaultThumbnailException(id, VaultError.Crypto.IntegrityFailure)

        // Hard rule 4: this ends up in a log or a crash breadcrumb. An id and an error code are
        // allowed there; a file name or a byte of the user's image is not.
        val text = thrown.toString() + thrown.message.orEmpty()
        assertFalse(text.contains("holiday"), "no name can reach it, because none is carried")
        assertTrue(text.contains("16"), "the id's size is the most it may say")
    }

    @Test
    fun the_cache_key_is_stable_across_equal_ids_and_differs_between_items() {
        val same = VaultThumbnailKey(ByteArray(16) { it.toByte() })
        val other = VaultThumbnailKey(ByteArray(16) { (it + 1).toByte() })

        // The loader's memory cache is keyed off this. Identity-based keys would miss on every
        // scroll — each re-emission of the listing builds fresh id arrays — and a key shared
        // between items would show one item's picture on another's tile.
        assertEquals(VaultThumbnailKey(id).cacheKey, same.cacheKey)
        assertNotEquals(same.cacheKey, other.cacheKey)
        assertEquals(VaultThumbnailKey(id), same)
        assertEquals(VaultThumbnailKey(id).hashCode(), same.hashCode())
    }

    @Test
    fun the_key_never_prints_anything_but_the_item_id() {
        val text = VaultThumbnailKey(id).toString()

        assertTrue(text.contains(VaultThumbnailKey(id).cacheKey))
        assertFalse(text.contains("["), "no raw array, which would also be unstable")
    }

    private fun fetcher(items: FakeVaultItems) =
        VaultThumbnailFetcher(VaultThumbnailKey(id), items)
}
