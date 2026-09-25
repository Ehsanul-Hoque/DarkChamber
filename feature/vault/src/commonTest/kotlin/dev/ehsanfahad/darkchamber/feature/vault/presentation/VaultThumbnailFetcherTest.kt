package dev.ehsanfahad.darkchamber.feature.vault.presentation

import coil3.fetch.SourceFetchResult
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import kotlinx.coroutines.test.runTest
import okio.IOException
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

    /**
     * A decoder that asks the fetched source for a **file path** must not get one.
     *
     * Coil's `ImageSource` spills its buffer to a temp file the moment something calls `file()`, and
     * that buffer is a decrypted preview. The temp file lands in the system temp directory, not the
     * vault's own `tmp/`, so the purge on lock never reaches it. No decoder in this loader's
     * component set asks today — which is exactly the kind of guarantee that lasts until someone
     * adds a decoder, so the filesystem handed to the source refuses to be written to at all.
     */
    @Test
    fun a_decoder_that_asks_for_a_file_is_refused_rather_than_handed_plaintext() = runTest {
        val items = FakeVaultItems(thumbnails = mapOf(id.toList() to Outcome.Ok(thumbnail)))

        val source = assertIs<SourceFetchResult>(fetcher(items).fetch()).source

        assertFailsWith<IOException> { source.file() }
        // And the bytes are still readable the way the decoders actually read them.
        assertContentEquals(thumbnail, source.source().readByteArray())
    }

    /**
     * Which failures are a tampering signal, and which are just a picture that would not load.
     *
     * The tile has one slot for every kind of failure, so without this the distinction the fetcher
     * takes such care to preserve dies one layer above it: a preview whose tag did not match draws
     * the same ordinary badge as a file type with no preview. The error-handling rule says an
     * integrity failure is never rendered as something else — and the inverse matters just as much,
     * since marking an intact item damaged because a decoder disliked its bytes is the worse lie of
     * the two.
     */
    @Test
    fun only_a_failed_integrity_tag_counts_as_tampering() {
        assertTrue(previewFailedIntegrity(VaultThumbnailException(id, VaultError.Crypto.IntegrityFailure)))
    }

    @Test
    fun an_ordinary_decode_failure_is_not_tampering() {
        // What Coil throws when it cannot make sense of perfectly authentic bytes. The item is
        // intact; only the preview is unusable.
        assertFalse(previewFailedIntegrity(IllegalStateException("unsupported image format")))
        assertFalse(previewFailedIntegrity(null), "and a slot with no throwable claims nothing")
    }

    @Test
    fun a_vault_failure_that_is_not_an_integrity_failure_is_not_tampering() {
        // A preview that could not be read off storage says nothing about whether anyone touched
        // it. Reporting a disk error as tampering would train the user to ignore the mark.
        val io = VaultThumbnailException(id, VaultError.Io(RuntimeException("unreadable")))

        assertFalse(previewFailedIntegrity(io))
    }

    private fun fetcher(items: FakeVaultItems) =
        VaultThumbnailFetcher(VaultThumbnailKey(id), items)
}
