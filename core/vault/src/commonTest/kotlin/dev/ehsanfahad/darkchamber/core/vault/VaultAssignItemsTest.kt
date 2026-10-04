package dev.ehsanfahad.darkchamber.core.vault

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Filing many items at once.
 *
 * The batching is the point. Every listing re-emits on any write to `items`, and the item listing
 * decrypts every row's metadata on each emission — so a per-item loop would turn one user action
 * into N notifications and N full re-decrypts of a list somebody is looking at. That cost is
 * invisible in a correctness test: a loop produces exactly the same final state, which is why the
 * emission count is asserted here directly.
 */
class VaultAssignItemsTest {

    @Test
    fun a_batch_files_every_item_it_was_given() = runTest {
        val fx = AlbumFixture()
        val album = (fx.objects.createAlbum("Receipts") as Outcome.Ok).value
        val ids = List(3) { fx.importInto(null, "file-$it.txt") }

        val summary = (fx.objects.assignItems(ids, album) as Outcome.Ok).value

        assertEquals(AssignSummary(requested = 3, assigned = 3), summary)
        assertTrue(summary.complete)
        assertEquals(
            3,
            (fx.catalog.listItems(ItemFilter.Album(album)) as Outcome.Ok).value.size,
            "all three are in the album",
        )
        assertEquals(emptyList(), (fx.catalog.listItems(ItemFilter.Unfiled) as Outcome.Ok).value)
        fx.close()
    }

    @Test
    fun a_null_destination_unfiles_the_batch() = runTest {
        val fx = AlbumFixture()
        val album = (fx.objects.createAlbum("Trip") as Outcome.Ok).value
        val ids = List(2) { fx.importInto(album, "file-$it.txt") }

        val summary = (fx.objects.assignItems(ids, albumId = null) as Outcome.Ok).value

        assertEquals(2, summary.assigned)
        assertEquals(2, (fx.catalog.listItems(ItemFilter.Unfiled) as Outcome.Ok).value.size)
        assertEquals(emptyList(), (fx.catalog.listItems(ItemFilter.Album(album)) as Outcome.Ok).value)
        fx.close()
    }

    /**
     * The honest partial. A selection is a list of ids the user chose some seconds ago, so one of
     * them being shredded in the meantime is ordinary — and its absence from the album is already
     * what they asked for. Reporting it as a failure would be wrong; reporting nothing would make a
     * half-move indistinguishable from a clean one.
     */
    @Test
    fun ids_that_are_no_longer_there_are_skipped_and_counted_not_failed() = runTest {
        val fx = AlbumFixture()
        val album = (fx.objects.createAlbum("Receipts") as Outcome.Ok).value
        val present = fx.importInto(null, "here.txt")
        val shredded = fx.importInto(null, "gone.txt")
        assertIs<Outcome.Ok<Unit>>(fx.objects.shredObject(shredded))

        val result = fx.objects.assignItems(listOf(present, shredded), album)

        val summary = (result as Outcome.Ok).value
        assertEquals(AssignSummary(requested = 2, assigned = 1), summary)
        assertEquals(1, summary.skipped)
        assertTrue(!summary.complete, "the caller can say 1 of 2 rather than implying all of it moved")
        assertEquals(1, (fx.catalog.listItems(ItemFilter.Album(album)) as Outcome.Ok).value.size)
        fx.close()
    }

    /**
     * A destination that does not exist is the one real error here: it would file items where no
     * album view can reach them and the unfiled listing will not show them either.
     */
    @Test
    fun a_destination_that_does_not_exist_fails_and_moves_nothing() = runTest {
        val fx = AlbumFixture()
        val ids = List(2) { fx.importInto(null, "file-$it.txt") }

        val result = fx.objects.assignItems(ids, byteArrayOf(0xFF.toByte()))

        assertIs<Outcome.Err>(result)
        assertIs<VaultError.Db>(result.error)
        assertEquals(
            2,
            (fx.catalog.listItems(ItemFilter.Unfiled) as Outcome.Ok).value.size,
            "neither item moved — the write is one statement, so it cannot half-apply",
        )
        fx.close()
    }

    @Test
    fun an_empty_batch_is_ok_and_moves_nothing() = runTest {
        val fx = AlbumFixture()
        val album = (fx.objects.createAlbum("Receipts") as Outcome.Ok).value

        val summary = (fx.objects.assignItems(emptyList(), album) as Outcome.Ok).value

        assertEquals(AssignSummary(requested = 0, assigned = 0), summary)
        assertTrue(summary.complete, "nothing asked for is nothing missed")
        fx.close()
    }

    /**
     * **The test the batching exists for.** A per-item loop reaches the same final state, so nothing
     * above would notice it; what it costs is one re-emission of the whole item listing per item,
     * each one re-opening every row's metadata.
     */
    @Test
    fun the_whole_batch_produces_one_listing_emission_not_one_per_item() = runTest {
        val fx = AlbumFixture()
        val album = (fx.objects.createAlbum("Receipts") as Outcome.Ok).value
        val ids = List(5) { fx.importInto(null, "file-$it.txt") }

        fx.objects.observeItems(ItemFilter.Album(album)).test {
            assertEquals(emptyList(), awaitItem(), "the album starts empty")

            assertIs<Outcome.Ok<AssignSummary>>(fx.objects.assignItems(ids, album))

            assertEquals(5, awaitItem().size, "one emission carrying the whole batch")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        fx.close()
    }

    /** The album grid's counts move once too, for the same reason. */
    @Test
    fun the_album_listing_also_sees_the_batch_as_one_change() = runTest {
        val fx = AlbumFixture()
        val album = (fx.objects.createAlbum("Receipts") as Outcome.Ok).value
        val ids = List(4) { fx.importInto(null, "file-$it.txt") }

        fx.objects.observeAlbums().test {
            assertEquals(0, awaitItem().single().itemCount)

            assertIs<Outcome.Ok<AssignSummary>>(fx.objects.assignItems(ids, album))

            assertEquals(4, awaitItem().single().itemCount, "the count arrives in one step")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        fx.close()
    }

    /** Moving items between albums is a reassignment, not an unfile-then-file. */
    @Test
    fun a_batch_can_move_items_from_one_album_to_another() = runTest {
        val fx = AlbumFixture()
        val from = (fx.objects.createAlbum("From") as Outcome.Ok).value
        val to = (fx.objects.createAlbum("To") as Outcome.Ok).value
        val ids = List(2) { fx.importInto(from, "file-$it.txt") }

        assertEquals(2, (fx.objects.assignItems(ids, to) as Outcome.Ok).value.assigned)

        assertEquals(emptyList(), (fx.catalog.listItems(ItemFilter.Album(from)) as Outcome.Ok).value)
        assertEquals(2, (fx.catalog.listItems(ItemFilter.Album(to)) as Outcome.Ok).value.size)
        assertEquals(
            emptyList(),
            (fx.catalog.listItems(ItemFilter.Unfiled) as Outcome.Ok).value,
            "they never passed through unfiled",
        )
        fx.close()
    }
}
