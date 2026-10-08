package dev.ehsanfahad.darkchamber.core.vault

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deleting an album keeps its items.
 *
 * The happy path is the easy half. The half this exists for is **being interrupted between the two
 * writes**: there is no transaction spanning them, so one of the two possible orders has to be
 * chosen for what it leaves behind when it fails. Clearing first leaves an album with nothing in it
 * — visible, harmless, deletable again. Deleting the row first would leave items pointing at an
 * album that is gone: missing from every album view, missing from the unfiled listing too because
 * their `album_id` is not null, and reachable only through the whole vault.
 *
 * Both orders read as equally sensible in a diff, so both failure directions are injected here.
 */
class VaultAlbumDeleteTest {

    @Test
    fun deleting_an_album_unfiles_its_items_and_destroys_nothing() = runTest {
        val fx = AlbumFixture()
        val id = (fx.objects.createAlbum("Receipts") as Outcome.Ok).value
        val first = fx.importInto(id, "one.txt")
        val second = fx.importInto(id, "two.txt")

        assertIs<Outcome.Ok<Unit>>(fx.objects.deleteAlbum(id))

        assertNull((fx.catalog.getAlbum(id) as Outcome.Ok).value, "the album is gone")
        assertEquals(
            2,
            (fx.catalog.listItems(ItemFilter.Unfiled) as Outcome.Ok).value.size,
            "and both items are unfiled rather than deleted",
        )
        // The strongest form of "nothing was destroyed": the items still open. Deleting their rows
        // would have taken the wrapped DEKs with them, and nothing could reverse that.
        assertIs<Outcome.Ok<ObjectMeta>>(fx.objects.openMeta(first))
        assertIs<Outcome.Ok<ObjectMeta>>(fx.objects.openMeta(second))
        fx.close()
    }

    @Test
    fun the_deleted_album_leaves_the_listing_and_its_items_appear_unfiled() = runTest {
        val fx = AlbumFixture()
        val id = (fx.objects.createAlbum("Trip") as Outcome.Ok).value
        fx.importInto(id, "photo.txt")

        fx.objects.observeAlbums().test {
            assertEquals(1, awaitItem().size, "listed to begin with")

            assertIs<Outcome.Ok<Unit>>(fx.objects.deleteAlbum(id))

            // Two writes, so the listing re-emits twice: once when the item is unfiled, once when
            // the album row goes. The second is the one that matters.
            assertEquals(0, awaitItem().single().itemCount, "first the album empties")
            assertEquals(emptyList(), awaitItem(), "then it leaves the grid")
            cancelAndIgnoreRemainingEvents()
        }

        fx.objects.observeItems(ItemFilter.Unfiled).test {
            assertEquals(1, awaitItem().size, "the item is where the user will look for it")
            cancelAndIgnoreRemainingEvents()
        }
        fx.close()
    }

    /**
     * Interrupted **after** the clear and before the row delete — the failure the chosen order is
     * chosen for. What is left is an album with nothing in it, which the user can simply delete
     * again.
     */
    @Test
    fun interrupted_before_the_album_row_is_deleted_it_leaves_an_empty_album_and_no_orphans() = runTest {
        val driver = FailingSqlDriver(testSqlDriver(), failOn = deletesAnAlbum)
        val fx = AlbumFixture(driver = driver)
        val id = (fx.objects.createAlbum("Receipts") as Outcome.Ok).value
        fx.importInto(id, "one.txt")
        fx.importInto(id, "two.txt")

        val result = fx.objects.deleteAlbum(id)

        assertIs<Outcome.Err>(result, "the caller is told the delete did not finish")
        assertIs<VaultError.Db>(result.error)
        assertEquals(1, driver.refusals, "the refusal landed on the album-row delete, not the clear")

        // The safe residue: the album is still there, holding nothing.
        assertNotNull((fx.catalog.getAlbum(id) as Outcome.Ok).value, "the album survives, so it can be deleted again")
        assertEquals(
            0,
            (fx.catalog.listItems(ItemFilter.Album(id)) as Outcome.Ok).value.size,
            "its items already left",
        )
        assertEquals(
            2,
            (fx.catalog.listItems(ItemFilter.Unfiled) as Outcome.Ok).value.size,
            "they are unfiled, which is a state every surface can show",
        )
        assertNoItemPointsAtAMissingAlbum(fx)
        fx.close()
    }

    /**
     * Interrupted **on** the clear, which is the first write. Nothing should have happened at all —
     * and in particular the album row must still be there, because deleting it after a failed clear
     * is precisely how items end up pointing at an album that is gone.
     */
    @Test
    fun interrupted_on_the_clear_it_changes_nothing_and_keeps_the_album_row() = runTest {
        val driver = FailingSqlDriver(testSqlDriver(), failOn = unfilesItems)
        val fx = AlbumFixture(driver = driver)
        val id = (fx.objects.createAlbum("Receipts") as Outcome.Ok).value
        fx.importInto(id, "one.txt")

        val result = fx.objects.deleteAlbum(id)

        assertIs<Outcome.Err>(result)
        assertNotNull(
            (fx.catalog.getAlbum(id) as Outcome.Ok).value,
            "the row delete must not run after a failed clear",
        )
        assertEquals(
            1,
            (fx.catalog.listItems(ItemFilter.Album(id)) as Outcome.Ok).value.size,
            "the item is still filed where it was",
        )
        assertNoItemPointsAtAMissingAlbum(fx)
        fx.close()
    }

    @Test
    fun deleting_an_empty_album_works_and_deleting_an_absent_one_is_not_an_error() {
        val fx = AlbumFixture()
        val id = (fx.objects.createAlbum("Empty") as Outcome.Ok).value

        assertIs<Outcome.Ok<Unit>>(fx.objects.deleteAlbum(id))
        // Already gone: both writes are no-ops and the user's intent is already true. Unlike a shred
        // of an unknown id, no destruction is being reported that did not happen.
        assertIs<Outcome.Ok<Unit>>(fx.objects.deleteAlbum(id))
        fx.close()
    }

    @Test
    fun deleting_one_album_leaves_another_albums_items_alone() {
        val fx = AlbumFixture()
        val doomed = (fx.objects.createAlbum("Doomed") as Outcome.Ok).value
        val kept = (fx.objects.createAlbum("Kept") as Outcome.Ok).value
        fx.importInto(doomed, "a.txt")
        val keptItem = fx.importInto(kept, "b.txt")

        assertIs<Outcome.Ok<Unit>>(fx.objects.deleteAlbum(doomed))

        val stillFiled = (fx.catalog.listItems(ItemFilter.Album(kept)) as Outcome.Ok).value
        assertEquals(1, stillFiled.size, "the other album is untouched")
        assertTrue(stillFiled.single().id.contentEquals(keptItem))
        fx.close()
    }

    /**
     * The invariant the ordering protects, checked directly: every item either has no album or has
     * one that exists. An item failing this is invisible in the album surfaces and findable only in
     * the whole-vault listing.
     */
    private fun assertNoItemPointsAtAMissingAlbum(fx: AlbumFixture) {
        val albumIds = (fx.catalog.listAlbums() as Outcome.Ok).value.map { it.id.toList() }
        val orphans = (fx.catalog.listItems() as Outcome.Ok).value
            .mapNotNull { it.album_id?.toList() }
            .filter { it !in albumIds }

        assertEquals(emptyList(), orphans, "no item may point at an album that does not exist")
    }
}
