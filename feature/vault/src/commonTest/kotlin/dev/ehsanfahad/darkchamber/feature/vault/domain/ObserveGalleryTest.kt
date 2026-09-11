package dev.ehsanfahad.darkchamber.feature.vault.domain

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.damagedMeta
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
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
            assertEquals(emptyList(), awaitItem())

            vault.summaries.value = listOf(
                summary(byteArrayOf(1), readableMeta("holiday.jpg", "image/jpeg"), size = 900L, createdAt = 5L),
            )

            val item = awaitItem().single()
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
                summary(byteArrayOf(1), readableMeta()),
                summary(byteArrayOf(2), damagedMeta()),
            )

            val items = awaitItem()
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

        ObserveGallery(vault).invoke(albumId = album).test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertContentEquals(album, vault.observedAlbum)
    }
}
