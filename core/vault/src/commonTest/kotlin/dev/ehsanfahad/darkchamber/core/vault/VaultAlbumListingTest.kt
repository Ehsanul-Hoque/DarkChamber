package dev.ehsanfahad.darkchamber.core.vault

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The album grid's listing: `observeAlbums` joins each album row to its decrypted name, and takes
 * the count and the cover from the catalog's plaintext columns without decrypting a single item.
 *
 * Two load-bearing cases live here. The **damaged album** — a row whose `enc_name` will not open
 * must still be listed, carrying its error, with its id, count and cover intact; an album dropped
 * from the grid hides tampering *and* strands whatever is filed inside it. And the **cost shape**:
 * the number of decrypts is the number of albums, never the number of items, which is what makes
 * this screen affordable in a large vault.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultAlbumListingTest {

    @Test
    fun an_album_is_listed_with_its_opened_name_count_and_cover() = runTest {
        val fx = AlbumFixture()
        val id = fx.putAlbum(1, fx.sealed("Receipts"), createdAt = 500L)
        fx.importInto(id, "older.txt")
        val newest = fx.importInto(id, "newer.txt")

        fx.objects.observeAlbums().test {
            val listed = awaitItem().single()

            assertEquals(Outcome.Ok("Receipts"), listed.name, "the name is opened for display")
            assertContentEquals(id, listed.id)
            assertEquals(2, listed.itemCount, "counted from the plaintext column, not from decrypting")
            assertContentEquals(newest, listed.coverItemId, "the newest item represents the album")
            assertEquals(500L, listed.createdAt)
            cancelAndIgnoreRemainingEvents()
        }
        fx.close()
    }

    /**
     * The case the whole `Outcome` is for. The damaged album keeps everything the catalog can still
     * tell us about it — which is everything except its name — so the user can see that it exists,
     * see how much is in it, and delete it.
     */
    @Test
    fun an_album_whose_name_will_not_open_is_listed_with_the_error() = runTest {
        val fx = AlbumFixture()
        fx.putAlbum(1, fx.sealed("Receipts"), createdAt = 100L)
        val brokenId = fx.putAlbum(2, fx.damaged("Payslips"), createdAt = 200L)
        val stranded = fx.importInto(brokenId, "in-the-damaged-album.txt")

        fx.objects.observeAlbums().test {
            val listed = awaitItem()

            assertEquals(2, listed.size, "the damaged album is present, not filtered out")
            val broken = listed.single { it.id.contentEquals(brokenId) }
            assertIs<Outcome.Err>(broken.name, "it carries why, rather than a blank or a placeholder")
            assertEquals(
                VaultError.Crypto.IntegrityFailure,
                broken.name.error,
                "surfaced as possible tampering",
            )
            assertEquals(1, broken.itemCount, "its count still reads — that is a plaintext column")
            assertContentEquals(stranded, broken.coverItemId, "and so does its cover")

            val fine = listed.single { it.id.contentEquals(byteArrayOf(1)) }
            assertEquals(Outcome.Ok("Receipts"), fine.name, "one damaged album must not poison its neighbour")
            cancelAndIgnoreRemainingEvents()
        }
        fx.close()
    }

    @Test
    fun an_empty_album_lists_with_no_cover_and_a_zero_count() = runTest {
        val fx = AlbumFixture()
        fx.putAlbum(1, fx.sealed("Empty"), createdAt = 100L)

        fx.objects.observeAlbums().test {
            val listed = awaitItem().single()

            assertEquals(0, listed.itemCount)
            assertNull(listed.coverItemId, "an album with nothing in it has nothing to show")
            assertEquals(Outcome.Ok("Empty"), listed.name, "and is still named")
            cancelAndIgnoreRemainingEvents()
        }
        fx.close()
    }

    /**
     * Liveness through the vault layer, not just the catalog's. The count and cover change while the
     * album row is untouched, so this also re-proves across this mapping that the listing follows
     * writes to `items`.
     */
    @Test
    fun the_listing_follows_item_writes_without_the_album_row_changing() = runTest {
        val fx = AlbumFixture()
        val id = fx.putAlbum(1, fx.sealed("Trip"), createdAt = 100L)

        fx.objects.observeAlbums().test {
            assertEquals(0, awaitItem().single().itemCount, "starts empty")

            val first = fx.importInto(id, "one.txt")
            // The import writes a row, then the filing writes it again — two writes, so two
            // emissions, and the first of them has not been filed yet.
            awaitItem()
            val afterFiling = awaitItem().single()
            assertEquals(1, afterFiling.itemCount, "the count follows a filing")
            assertContentEquals(first, afterFiling.coverItemId)

            assertIs<Outcome.Ok<Unit>>(fx.objects.shredObject(first))
            val afterShred = awaitItem().single()
            assertEquals(0, afterShred.itemCount, "and follows a shred")
            assertNull(afterShred.coverItemId, "losing its last item loses its cover, not its row")
            cancelAndIgnoreRemainingEvents()
        }
        fx.close()
    }

    /**
     * The name unseal is AES-GCM plus a CBOR decode, and it must not run wherever the caller
     * collects — on the album grid that is the UI thread, once per album, on every re-emission.
     * Counted the same way the item listing counts it: without the `flowOn` the mapping stays in the
     * collector's context and this dispatcher is never touched.
     */
    @Test
    fun the_name_decrypt_runs_off_the_collectors_context() = runTest {
        // A dispatcher sharing runTest's scheduler, so the work it counts actually runs — wrapping
        // Unconfined does not work, for the reason the item listing's equivalent records.
        val counting = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val fx = AlbumFixture(mapContext = counting)
        fx.putAlbum(1, fx.sealed("Trip"), createdAt = 100L)

        fx.objects.observeAlbums().test {
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(counting.dispatches > 0, "the name mapping must be dispatched to the map context")
        fx.close()
    }

    /**
     * The cost shape, counted rather than claimed: one AEAD open per album per emission, and none
     * per item.
     *
     * This is the property the whole design of the listing rests on, and it is invisible in every
     * other test here — a listing that derived a count, a cover or a total size from item metadata
     * would still produce the right numbers and simply cost one open per file per emission, on a
     * screen that re-emits whenever anything is imported. The opens are tallied through a counting
     * engine wrapping the real one, so the measurement is of the real session's work.
     */
    @Test
    fun the_listing_opens_one_blob_per_album_and_none_per_item() = runTest {
        val counting = CountingCryptoEngine(CryptographyKotlinEngine())
        val fx = AlbumFixture(engine = counting)
        val trip = fx.putAlbum(1, fx.sealed("Trip"), createdAt = 100L)
        fx.putAlbum(2, fx.sealed("Receipts"), createdAt = 200L)
        repeat(5) { fx.importInto(trip, "file-$it.txt") }

        // Measured from here: the imports above open blobs of their own, and those are not what this
        // is about.
        val before = counting.opens
        fx.objects.observeAlbums().test {
            val listed = awaitItem()
            assertEquals(listOf(5, 0), listed.map { it.itemCount }, "five items in one album, none in the other")
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            2,
            counting.opens - before,
            "two albums, two opens — not seven, which is what reading the items would cost",
        )
        fx.close()
    }
}
