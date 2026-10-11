package dev.ehsanfahad.darkchamber.feature.vault.domain

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.AlbumSummary
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Naming the album a filed item is in, on the one listing that mixes filed and unfiled items.
 *
 * The constraint this is written against: **no per-item decrypt.** An item's row carries `album_id`
 * as a plaintext column, but the album's *name* is sealed in the albums table — so the name comes
 * from the album listing that already exists (one small decrypt per album, nothing per item) and is
 * mapped in memory. Opening a name per item row would turn a cheap screen into one that scales with
 * the vault, against a documented decision (docs/ARCHITECTURE.md §5.1).
 */
class ObserveGalleryAlbumsTest {

    private val album = byteArrayOf(9)

    private fun albumSummary(
        id: ByteArray = album,
        name: Outcome<String> = Outcome.Ok("Trip"),
    ) = AlbumSummary(id, name, itemCount = 1, coverItemId = null, createdAt = 1L)

    /**
     * An item filed in [album], holding its own **copy** of the id.
     *
     * The copy is the point. In production the two ids arrive from two separate reads, so they are
     * never the same instance — and `ByteArray` equality is identity, so a lookup keyed by the array
     * itself would miss and every filed item would look unfiled. Sharing one instance across the
     * fixture would hide exactly that bug: the mutation to identity keys passed until this copy was
     * added.
     */
    private fun filedItem(id: Byte) = summary(byteArrayOf(id), readableMeta(), albumId = album.copyOf())

    @Test
    fun a_filed_item_is_labelled_with_its_albums_name() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(albumSummary())
        vault.summaries.value = listOf(filedItem(1))

        ObserveGallery(vault).invoke().test {
            val item = awaitItem().items.single()
            assertEquals(AlbumTileName.Readable("Trip"), item.album)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun an_unfiled_item_carries_no_label_at_all() = runTest {
        // An absence is the honest marker for "in no album"; a badge on every tile would be noise on
        // the common case.
        val vault = FakeVaultItems()
        vault.albums.value = listOf(albumSummary())
        vault.summaries.value = listOf(summary(byteArrayOf(1), readableMeta()))

        ObserveGallery(vault).invoke().test {
            assertNull(awaitItem().items.single().album)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun an_album_whose_name_will_not_open_labels_its_items_unverifiably() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(albumSummary(name = Outcome.Err(VaultError.Crypto.IntegrityFailure)))
        vault.summaries.value = listOf(filedItem(1))

        ObserveGallery(vault).invoke().test {
            // Never a blank and never a guess: the same honest form the album grid uses.
            assertEquals(AlbumTileName.Unreadable, awaitItem().items.single().album)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun an_item_whose_album_is_not_in_the_listing_is_simply_unlabelled() = runTest {
        // An ordinary race: the album was deleted between the two listings emitting. Labelling it
        // with anything would be inventing a name, and failing the listing would hide every item.
        val vault = FakeVaultItems()
        vault.albums.value = emptyList()
        vault.summaries.value = listOf(filedItem(1))

        ObserveGallery(vault).invoke().test {
            assertNull(awaitItem().items.single().album)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun renaming_an_album_relabels_its_items_without_a_second_item_subscription() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(albumSummary())
        vault.summaries.value = listOf(filedItem(1))

        ObserveGallery(vault).invoke().test {
            assertEquals(AlbumTileName.Readable("Trip"), awaitItem().items.single().album)

            vault.albums.value = listOf(albumSummary(name = Outcome.Ok("Alps")))

            assertEquals(AlbumTileName.Readable("Alps"), awaitItem().items.single().album)
            assertEquals(1, vault.observeCalls, "a rename must not re-read the items")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun the_album_listing_is_not_subscribed_inside_an_album() = runTest {
        // Inside an album every item is in that album, so the label would repeat the heading on
        // every tile — and a screen that shows no labels must not pay for the listing that feeds them.
        val vault = FakeVaultItems()

        ObserveGallery(vault).invoke(filter = ItemFilter.Album(album)).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(0, vault.observeAlbumsCalls, "an album screen must not read the album listing")
    }

    /**
     * And the items on that screen carry **no label**, which is what the tile's badge relies on to
     * stay off every tile inside an album — the rendering has no scope of its own to check, so this
     * is where "only on the whole vault" is actually decided.
     */
    @Test
    fun an_item_inside_an_album_carries_no_label_to_draw() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(albumSummary())
        val filter = ItemFilter.Album(album)
        vault.summariesByFilter[filter] = MutableStateFlow(listOf(filedItem(1)))

        ObserveGallery(vault).invoke(filter = filter).test {
            // The item *is* filed — it carries the album's id — and still gets nothing, because the
            // heading already says which album this is.
            assertNull(awaitItem().items.single().album)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun the_album_listing_is_not_subscribed_in_unfiled() = runTest {
        val vault = FakeVaultItems()

        ObserveGallery(vault).invoke(filter = ItemFilter.Unfiled).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(0, vault.observeAlbumsCalls, "nothing in Unfiled has an album to name")
    }

    @Test
    fun the_whole_vault_listing_subscribes_to_the_album_listing_once() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(albumSummary())

        ObserveGallery(vault).invoke().test {
            awaitItem()
            vault.summaries.value = listOf(filedItem(1))
            awaitItem()
            vault.summaries.value = listOf(
                filedItem(1),
                summary(byteArrayOf(2), readableMeta()),
            )
            awaitItem()

            // Once for the screen, not once per emission and not once per item: the label's cost is
            // fixed, while the listing it labels grows.
            assertEquals(1, vault.observeAlbumsCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun an_items_tostring_never_prints_the_album_name() = runTest {
        val vault = FakeVaultItems()
        vault.albums.value = listOf(albumSummary(name = Outcome.Ok("Berlin trip")))
        vault.summaries.value = listOf(filedItem(1))

        ObserveGallery(vault).invoke().test {
            val printed = awaitItem().items.single().toString()
            // An album name is decrypted vault content, like a file name (hard rule 4).
            assertTrue("Berlin" !in printed, "an album name must never be printed, was $printed")
            assertTrue("filed" in printed, "but whether an item is filed is safe to say, was $printed")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
