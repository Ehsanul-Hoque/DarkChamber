package dev.ehsanfahad.darkchamber.core.vault

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The live item count a screen shows beside a listing — how many items the whole vault, the unfiled
 * items or one album holds.
 *
 * The property worth a test of its own is the **cost**: a count read from the plaintext index opens
 * nothing, while a count taken by sizing [VaultObjects.observeItems] would open every row's metadata
 * on every emission. Both give the right number, so only counting the opens tells them apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultItemCountTest {

    @Test
    fun each_filter_counts_its_own_items() = runTest {
        val fx = AlbumFixture()
        val trip = fx.putAlbum(1, fx.sealed("Trip"))
        val receipts = fx.putAlbum(2, fx.sealed("Receipts"))
        fx.importInto(trip, "a.txt")
        fx.importInto(trip, "b.txt")
        fx.importInto(receipts, "c.txt")
        fx.importInto(null, "d.txt")

        fx.objects.observeItemCount(ItemFilter.All).test {
            assertEquals(4L, awaitItem(), "the whole vault counts filed and unfiled alike")
            cancelAndIgnoreRemainingEvents()
        }
        fx.objects.observeItemCount(ItemFilter.Unfiled).test {
            assertEquals(1L, awaitItem(), "only the item with no album")
            cancelAndIgnoreRemainingEvents()
        }
        // Its own copy of the id, as a caller holding an id decoded elsewhere would have.
        fx.objects.observeItemCount(ItemFilter.Album(trip.copyOf())).test {
            assertEquals(2L, awaitItem(), "only that album's items")
            cancelAndIgnoreRemainingEvents()
        }
        fx.close()
    }

    /**
     * Counted through an engine wrapping the real one. The emissions are driven by **catalog-only**
     * moves, which touch no crypto themselves, so every open tallied while the count is subscribed
     * belongs to the count — and a count that opened anything per item or per emission shows up as
     * a non-zero tally. The imports that build the vault happen before measuring starts.
     */
    @Test
    fun the_count_opens_nothing_however_many_items_and_emissions() = runTest {
        val counting = CountingCryptoEngine(CryptographyKotlinEngine())
        val fx = AlbumFixture(engine = counting)
        val trip = fx.putAlbum(1, fx.sealed("Trip"))
        val elsewhere = fx.putAlbum(2, fx.sealed("Elsewhere"))
        val ids = List(5) { fx.importInto(trip, "file-$it.txt") }

        val before = counting.opens
        fx.objects.observeItemCount(ItemFilter.Album(trip.copyOf())).test {
            assertEquals(5L, awaitItem())

            fx.catalog.updateItemAlbum(ids.take(2), elsewhere)
            assertEquals(3L, awaitItem(), "moving two out lowers the count")

            fx.catalog.updateItemAlbum(ids.take(2), trip)
            assertEquals(5L, awaitItem(), "moving them back raises it")

            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(0, counting.opens - before, "three emissions over five items, and nothing opened")
        fx.close()
    }
}
