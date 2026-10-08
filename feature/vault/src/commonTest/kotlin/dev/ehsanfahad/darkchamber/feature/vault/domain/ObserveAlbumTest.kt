package dev.ehsanfahad.darkchamber.feature.vault.domain

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.AlbumSummary
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Watching one album: its name, and whether it is still there.
 *
 * The presence half is why this exists. An album deleted while being looked at does not empty its item
 * listing in any way that screen can recognise — the items are unfiled rather than destroyed, so the
 * grid simply goes empty, which is indistinguishable from an album that was always empty.
 */
class ObserveAlbumTest {

    private val id = byteArrayOf(1)

    private fun album(
        id: Byte,
        name: Outcome<String>,
        itemCount: Int = 0,
        coverItemId: ByteArray? = null,
    ) = AlbumSummary(byteArrayOf(id), name, itemCount, coverItemId, createdAt = 100L)

    @Test
    fun a_present_album_carries_its_name() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(1, Outcome.Ok("Receipts")))

        ObserveAlbum(vault)(id).test {
            assertEquals(AlbumPresence.Present(AlbumTileName.Readable("Receipts")), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A name that will not open is **mapped, not treated as absence**. The album is still there, still
     * holds its items, and must still be deletable — reporting it as gone would strand everything in it
     * behind a notice saying it does not exist.
     */
    @Test
    fun an_unreadable_name_is_still_a_present_album() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(1, Outcome.Err(VaultError.Crypto.IntegrityFailure)))

        ObserveAlbum(vault)(id).test {
            assertEquals(AlbumPresence.Present(AlbumTileName.Unreadable), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun an_album_that_is_not_in_the_listing_is_gone() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(2, Outcome.Ok("Somebody else's")))

        ObserveAlbum(vault)(id).test {
            assertEquals(AlbumPresence.Gone, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Deleted from elsewhere while the screen was open: Present, then Gone, with nothing in between. */
    @Test
    fun an_album_that_disappears_reports_gone() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(1, Outcome.Ok("Receipts")))

        ObserveAlbum(vault)(id).test {
            assertEquals(AlbumPresence.Present(AlbumTileName.Readable("Receipts")), awaitItem())

            vault.albums.value = emptyList()

            assertEquals(AlbumPresence.Gone, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * **An item change must not re-emit.** The album listing re-emits on every catalog change — every
     * import, every assignment — carrying a fresh count and possibly a fresh cover while the name and
     * the presence are untouched. Each of those would otherwise republish an identical heading and, on
     * the screen above, step on an open rename dialog.
     */
    @Test
    fun a_changed_count_or_cover_does_not_re_emit() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(1, Outcome.Ok("Receipts"), itemCount = 1))

        ObserveAlbum(vault)(id).test {
            assertEquals(AlbumPresence.Present(AlbumTileName.Readable("Receipts")), awaitItem())

            // What an import into this album does to the listing: same album, same name, new count and
            // a cover it did not have before.
            vault.albums.value = listOf(
                album(1, Outcome.Ok("Receipts"), itemCount = 2, coverItemId = byteArrayOf(9)),
            )

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A rename does re-emit, which is the other half of the same rule. */
    @Test
    fun a_changed_name_re_emits() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(1, Outcome.Ok("Receipts")))

        ObserveAlbum(vault)(id).test {
            assertEquals(AlbumPresence.Present(AlbumTileName.Readable("Receipts")), awaitItem())

            vault.albums.value = listOf(album(1, Outcome.Ok("Invoices")))

            assertEquals(AlbumPresence.Present(AlbumTileName.Readable("Invoices")), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
