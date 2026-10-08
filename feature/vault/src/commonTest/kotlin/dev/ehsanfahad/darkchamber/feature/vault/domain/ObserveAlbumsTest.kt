package dev.ehsanfahad.darkchamber.feature.vault.domain

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.AlbumSummary
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Albums as tiles: the mapping, and the order they come out in.
 *
 * Two things are load-bearing. An album whose **name will not open is mapped, not filtered** — it
 * still holds files, and dropping it would hide tampering while stranding what is inside it. And the
 * order is **alphabetical and total**: duplicate names are allowed, so equal names are ordinary
 * rather than a corner, and without a tiebreak two albums could swap places between emissions of a
 * listing that re-emits on every import.
 */
class ObserveAlbumsTest {

    private fun album(
        id: Byte,
        name: Outcome<String>,
        itemCount: Int = 0,
        coverItemId: ByteArray? = null,
        createdAt: Long = 100L,
    ) = AlbumSummary(byteArrayOf(id), name, itemCount, coverItemId, createdAt)

    private fun readable(name: String): Outcome<String> = Outcome.Ok(name)

    private val damaged: Outcome<String> = Outcome.Err(VaultError.Crypto.IntegrityFailure)

    @Test
    fun a_summary_becomes_a_tile_carrying_everything_the_grid_draws() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(
            album(1, readable("Receipts"), itemCount = 4, coverItemId = byteArrayOf(9), createdAt = 500L),
        )

        ObserveAlbums(vault)().test {
            val tile = awaitItem().single()

            assertContentEquals(byteArrayOf(1), tile.id)
            assertEquals(AlbumTileName.Readable("Receipts"), tile.name)
            assertEquals(4, tile.itemCount)
            assertContentEquals(byteArrayOf(9), tile.coverItemId)
            assertEquals(500L, tile.createdAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun an_empty_album_keeps_its_absent_cover() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(1, readable("Empty")))

        ObserveAlbums(vault)().test {
            assertNull(awaitItem().single().coverItemId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun albums_are_alphabetical_rather_than_oldest_first() = runTest {
        val vault = FakeVaultItems()
        // Deliberately in creation order, which is what the catalog hands over.
        vault.albums.value = listOf(
            album(1, readable("Work"), createdAt = 100L),
            album(2, readable("Archive"), createdAt = 200L),
            album(3, readable("Receipts"), createdAt = 300L),
        )

        ObserveAlbums(vault)().test {
            assertEquals(
                listOf("Archive", "Receipts", "Work"),
                awaitItem().map { (it.name as AlbumTileName.Readable).value },
                "an album list is something you come back to, so it keeps things where you left them",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Capitalisation is not an ordering the user asked for.
     *
     * The names here are chosen so the two orderings actually differ: a plain `String` comparison is
     * by code point, so every capitalised name would sort ahead of every lowercase one — "Bananas"
     * before "apples" — and a user who capitalised one album would find it filed in a different part
     * of the list for that reason alone. An earlier version of this test used names that happened to
     * sort identically either way and so proved nothing.
     */
    @Test
    fun the_order_ignores_case() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(
            album(1, readable("Bananas")),
            album(2, readable("apples")),
            album(3, readable("Cherries")),
        )

        ObserveAlbums(vault)().test {
            assertEquals(
                listOf("apples", "Bananas", "Cherries"),
                awaitItem().map { (it.name as AlbumTileName.Readable).value },
                "lowercase 'apples' leads, which a code-point comparison would never do",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Where an unopenable name lands, decided rather than left to chance: **last**. It has no name
     * to sort by, so any position is arbitrary — but it must be the same arbitrary position every
     * time, or the grid reshuffles whenever anything is imported.
     */
    @Test
    fun an_unreadable_name_is_listed_last_and_never_dropped() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(
            album(1, damaged, itemCount = 3, coverItemId = byteArrayOf(7)),
            album(2, readable("Zebra")),
            album(3, readable("Apples")),
        )

        ObserveAlbums(vault)().test {
            val tiles = awaitItem()

            assertEquals(3, tiles.size, "the damaged album is present, not filtered out")
            assertEquals(
                listOf("Apples", "Zebra"),
                tiles.dropLast(1).map { (it.name as AlbumTileName.Readable).value },
            )
            val broken = tiles.last()
            assertEquals(AlbumTileName.Unreadable, broken.name)
            assertEquals(3, broken.itemCount, "its count still renders — that came from a plaintext column")
            assertContentEquals(byteArrayOf(7), broken.coverItemId, "and so does its cover")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun several_unreadable_names_keep_a_stable_order_among_themselves() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(
            album(3, damaged, createdAt = 300L),
            album(1, damaged, createdAt = 100L),
            album(2, damaged, createdAt = 200L),
        )

        ObserveAlbums(vault)().test {
            assertEquals(
                listOf<Byte>(1, 2, 3),
                awaitItem().map { it.id.single() },
                "by date, so the grid does not reshuffle them on every re-emission",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Duplicate names are allowed by decision, so two albums called the same thing are an ordinary
     * case. Without a tiebreak they would come out in whatever order the catalog returned, and the
     * listing re-emits on every import — so they could swap places while the user watched.
     */
    @Test
    fun albums_sharing_a_name_are_ordered_by_date_then_id() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(
            album(9, readable("Holiday"), createdAt = 200L),
            album(2, readable("Holiday"), createdAt = 100L),
            album(1, readable("Holiday"), createdAt = 200L),
        )

        ObserveAlbums(vault)().test {
            assertEquals(
                listOf<Byte>(2, 1, 9),
                awaitItem().map { it.id.single() },
                "oldest first, and the two same-dated ones fall back to a total order of their own",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The same input twice is the same output twice — a re-emission must not reshuffle anything. */
    @Test
    fun the_order_does_not_drift_between_emissions() = runTest {
        val vault = FakeVaultItems()
        val listing = listOf(
            album(1, readable("Holiday"), createdAt = 100L),
            album(2, readable("holiday"), createdAt = 100L),
            album(3, damaged, createdAt = 100L),
            album(4, readable("Archive"), createdAt = 100L),
        )

        // Set before collecting, so the first emission is the listing rather than the fake's own
        // empty starting state.
        vault.albums.value = listing

        ObserveAlbums(vault)().test {
            val first = awaitItem().map { it.id.single() }

            vault.albums.value = listing.reversed()
            val second = awaitItem().map { it.id.single() }

            assertEquals(first, second, "the catalog's own order must not leak through")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun an_empty_listing_maps_to_no_tiles() = runTest {
        val vault = FakeVaultItems()

        ObserveAlbums(vault)().test {
            assertEquals(emptyList(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The tile is as loggable as the item tile beside it, so the name must not survive printing. */
    @Test
    fun a_tiles_toString_does_not_print_the_name() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(album(1, readable("Passport scans")))

        ObserveAlbums(vault)().test {
            val rendered = awaitItem().single().toString()

            assertEquals(false, rendered.contains("Passport"), "the name must not appear in $rendered")
            assertEquals(true, rendered.contains("itemCount=0"), "counts are fine: $rendered")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
