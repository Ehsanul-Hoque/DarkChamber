package dev.ehsanfahad.darkchamber.feature.vault.domain

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
import dev.ehsanfahad.darkchamber.feature.vault.damagedMeta
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObserveGalleryTest {

    @Test
    fun summaries_become_gallery_items() = runTest {
        val vault = FakeVaultItems()
        val observe = ObserveGallery(vault)

        observe().test {
            assertEquals(emptyList(), awaitItem().items)

            vault.summaries.value = listOf(
                summary(byteArrayOf(1), readableMeta("holiday.jpg", "image/jpeg"), size = 900L, createdAt = 5L),
            )

            val item = awaitItem().items.single()
            assertContentEquals(byteArrayOf(1), item.id)
            assertEquals(VaultItemKind.FILE, item.kind)
            assertEquals(900L, item.size)
            assertEquals(5L, item.createdAt)
            assertEquals(GalleryItemMeta.Readable("holiday.jpg", "image/jpeg"), item.meta)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The load-bearing case: an item whose metadata will not open is *shown* as unreadable. Dropping
     * it would hide tampering, and its row is still there to be shredded.
     */
    @Test
    fun a_damaged_summary_becomes_an_unreadable_item_not_a_dropped_one() = runTest {
        val vault = FakeVaultItems()
        val observe = ObserveGallery(vault)

        observe().test {
            awaitItem()
            vault.summaries.value = listOf(
                summary(byteArrayOf(1), readableMeta(), createdAt = 2L),
                summary(byteArrayOf(2), damagedMeta(), createdAt = 1L),
            )

            val items = awaitItem().items
            assertEquals(2, items.size, "the damaged item is still listed")
            assertEquals(GalleryItemMeta.Unreadable, items[1].meta)
            assertTrue(items[0].meta is GalleryItemMeta.Readable, "one damaged item must not poison the rest")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun the_album_filter_is_passed_through() = runTest {
        val vault = FakeVaultItems()
        val album = byteArrayOf(0xC3.toByte())

        ObserveGallery(vault).invoke(ItemFilter.Album(album)).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(ItemFilter.Album(album), vault.observedFilter)
    }

    /**
     * Unfiled has to survive the trip as itself. It is the case a nullable id could not carry, so
     * the use case collapsing it to All — the shape the old signature's default had — is the exact
     * regression this filter exists to prevent, and it would look like a working screen that simply
     * showed too much.
     */
    @Test
    fun the_unfiled_filter_is_passed_through_as_itself() = runTest {
        val vault = FakeVaultItems()

        ObserveGallery(vault).invoke(ItemFilter.Unfiled).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(ItemFilter.Unfiled, vault.observedFilter)
    }

    /** And the default is the whole vault, which is what the gallery asks for with no album. */
    @Test
    fun no_filter_means_the_whole_vault() = runTest {
        val vault = FakeVaultItems()

        ObserveGallery(vault).invoke().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(ItemFilter.All, vault.observedFilter)
    }

    @Test
    fun the_chosen_order_reaches_the_listing() = runTest {
        val vault = FakeVaultItems()
        val controls = MutableStateFlow(GalleryControls())
        vault.summaries.value = listOf(
            summary(byteArrayOf(1), readableMeta("zebra.txt"), createdAt = 30L),
            summary(byteArrayOf(2), readableMeta("apple.txt"), createdAt = 10L),
        )

        ObserveGallery(vault).invoke(controls = controls).test {
            assertContentEquals(byteArrayOf(1), awaitItem().items.first().id, "newest is the default")

            controls.value = GalleryControls(order = GalleryOrder.NameAsc)
            assertContentEquals(byteArrayOf(2), awaitItem().items.first().id, "A-Z puts apple first")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun the_query_filters_the_listing_and_reports_what_it_hid() = runTest {
        val vault = FakeVaultItems()
        val controls = MutableStateFlow(GalleryControls())
        vault.summaries.value = listOf(
            summary(byteArrayOf(1), readableMeta("tax.pdf"), createdAt = 30L),
            summary(byteArrayOf(2), readableMeta("cat.png"), createdAt = 20L),
            summary(byteArrayOf(3), damagedMeta(), createdAt = 10L),
        )

        ObserveGallery(vault).invoke(controls = controls).test {
            assertEquals(3, awaitItem().items.size, "no query shows everything, damaged included")

            controls.value = GalleryControls(query = "tax")
            val searched = awaitItem()
            assertContentEquals(byteArrayOf(1), searched.items.single().id)
            assertEquals(1, searched.hiddenUnreadable, "the damaged item is accounted for, not dropped quietly")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The performance design, asserted as a design rather than as a feeling: the listing subscribes
     * **once per filter**, and the order and the query apply downstream of it.
     *
     * If either were part of the flow's key — a `flatMapLatest` over filter plus text — every
     * keystroke would re-subscribe, and every re-subscription re-decrypts every row's metadata. That
     * is the one cost this cycle could have added, so the subscription count is what the test watches
     * rather than anything about the items it gets back.
     */
    @Test
    fun changing_the_order_or_the_query_never_resubscribes() = runTest {
        val vault = FakeVaultItems()
        val controls = MutableStateFlow(GalleryControls())
        vault.summaries.value = listOf(summary(byteArrayOf(1), readableMeta("tax.pdf")))

        ObserveGallery(vault).invoke(controls = controls).test {
            awaitItem()
            assertEquals(1, vault.observeCalls, "one subscription to start with")

            controls.value = GalleryControls(order = GalleryOrder.NameAsc)
            awaitItem()
            controls.value = GalleryControls(order = GalleryOrder.NameAsc, query = "t")
            awaitItem()
            controls.value = GalleryControls(order = GalleryOrder.NameAsc, query = "ta")
            awaitItem()
            controls.value = GalleryControls(order = GalleryOrder.NameAsc, query = "tax")
            awaitItem()

            assertEquals(1, vault.observeCalls, "four control changes must not touch the subscription")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun a_catalog_change_still_re_runs_the_pipeline() = runTest {
        val vault = FakeVaultItems()
        val controls = MutableStateFlow(GalleryControls(query = "tax"))

        ObserveGallery(vault).invoke(controls = controls).test {
            assertEquals(emptyList(), awaitItem().items)

            vault.summaries.value = listOf(summary(byteArrayOf(1), readableMeta("tax.pdf")))
            assertEquals(1, awaitItem().items.size, "a new row is searched as it arrives")

            vault.summaries.value = emptyList()
            assertEquals(emptyList(), awaitItem().items, "and a shred leaves the results")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Search narrows the listing you are standing on and never adds to it.
     *
     * This is what lets the album heading and the filed-item badge promise what they promise: inside
     * an album, every item on screen is in that album — including while a search is running.
     */
    @Test
    fun a_search_inside_an_album_cannot_reach_an_item_outside_it() = runTest {
        val vault = FakeVaultItems()
        val album = byteArrayOf(9)
        val inAlbum = summary(byteArrayOf(1), readableMeta("album-tax.pdf"), albumId = album)
        val elsewhere = summary(byteArrayOf(2), readableMeta("other-tax.pdf"))
        vault.summariesByFilter[ItemFilter.Album(album)] = MutableStateFlow(listOf(inAlbum))
        vault.summariesByFilter[ItemFilter.All] = MutableStateFlow(listOf(inAlbum, elsewhere))

        ObserveGallery(vault).invoke(
            filter = ItemFilter.Album(album),
            controls = MutableStateFlow(GalleryControls(query = "tax")),
        ).test {
            val items = awaitItem().items
            assertEquals(1, items.size, "the album's own listing is what gets searched")
            assertContentEquals(byteArrayOf(1), items.single().id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun a_search_in_unfiled_cannot_reach_a_filed_item() = runTest {
        val vault = FakeVaultItems()
        val filed = summary(byteArrayOf(1), readableMeta("filed-tax.pdf"), albumId = byteArrayOf(9))
        val loose = summary(byteArrayOf(2), readableMeta("loose-tax.pdf"))
        vault.summariesByFilter[ItemFilter.Unfiled] = MutableStateFlow(listOf(loose))
        vault.summariesByFilter[ItemFilter.All] = MutableStateFlow(listOf(filed, loose))

        ObserveGallery(vault).invoke(
            filter = ItemFilter.Unfiled,
            controls = MutableStateFlow(GalleryControls(query = "tax")),
        ).test {
            assertContentEquals(byteArrayOf(2), awaitItem().items.single().id)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
