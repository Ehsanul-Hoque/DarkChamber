package dev.ehsanfahad.darkchamber.feature.camera.domain

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.camera.FakeCaptureVault
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraDestination
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * What the camera's top bar says about where photos are going: the destination's title, its live
 * count, and whether an album destination still exists.
 */
class ObserveDestinationTest {

    private val vault = FakeCaptureVault()
    private val observe = ObserveDestination(vault)

    @Test
    fun the_whole_vault_reads_all_items_with_the_whole_vaults_count() = runTest {
        vault.counts[ItemFilter.All]!!.value = 142L

        observe(CameraDestination.AllItems).test {
            assertEquals(DestinationInfo(DestinationTitle.AllItems, count = 142L), awaitItem())
            vault.counts[ItemFilter.All]!!.value = 143L
            assertEquals(143L, awaitItem().count, "the count follows the vault")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun unfiled_reads_unfiled_with_the_unfiled_count() = runTest {
        vault.counts[ItemFilter.Unfiled]!!.value = 7L

        observe(CameraDestination.Unfiled).test {
            assertEquals(DestinationInfo(DestinationTitle.Unfiled, count = 7L), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The album's name and its count arrive together from the album listing — one subscription,
     * one small decrypt per album, nothing per item. The destination is handed **its own copy** of
     * the id, as it would be after travelling through a navigation configuration.
     */
    @Test
    fun an_album_reads_its_name_and_its_count_from_the_album_listing_alone() = runTest {
        vault.albums.value = listOf(album(1, "Trip to Sylhet", 12), album(2, "Receipts", 3))

        observe(CameraDestination.Album(byteArrayOf(1))).test {
            assertEquals(DestinationInfo(DestinationTitle.Album("Trip to Sylhet"), count = 12L), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, vault.countSubscriptions, "an album's count never needs the item count")
        assertEquals(1, vault.albumSubscriptions)
    }

    /** A failed GCM tag on the name is all that is known — the album is listed, its count still reads. */
    @Test
    fun an_album_whose_name_will_not_open_keeps_its_count() = runTest {
        vault.albums.value = listOf(album(1, Outcome.Err(VaultError.Crypto.IntegrityFailure), 4))

        observe(CameraDestination.Album(byteArrayOf(1))).test {
            assertEquals(DestinationInfo(DestinationTitle.UnreadableAlbum, count = 4L), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun an_album_that_leaves_the_listing_is_gone() = runTest {
        vault.albums.value = listOf(album(1, "Trip", 2))

        observe(CameraDestination.Album(byteArrayOf(1))).test {
            assertEquals(DestinationTitle.Album("Trip"), awaitItem().title)
            vault.albums.value = emptyList()
            assertEquals(DestinationInfo(DestinationTitle.AlbumGone, count = null), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A catalog that cannot be read must not look like an empty destination: a count of zero is a
     * claim about the vault. It reads as unknown instead, and the title keeps what it can.
     */
    @Test
    fun a_listing_that_fails_to_read_shows_no_count_rather_than_zero() = runTest {
        vault.countFailure = IllegalStateException("driver")

        observe(CameraDestination.AllItems).test {
            assertEquals(DestinationInfo(DestinationTitle.AllItems, count = null), awaitItem())
            awaitComplete()
        }
    }

    /**
     * An album listing that cannot be read says nothing about the album — not its name, not its
     * count, and not that it is gone, which would stop the shutter on a guess.
     */
    @Test
    fun an_album_listing_that_fails_to_read_says_nothing_about_the_album() = runTest {
        vault.albumFailure = IllegalStateException("driver")

        observe(CameraDestination.Album(byteArrayOf(1))).test {
            assertEquals(DestinationInfo(DestinationTitle.Unknown, count = null), awaitItem())
            awaitComplete()
        }
    }

    /** An album's name is decrypted content; whatever prints the destination must not print it. */
    @Test
    fun an_album_title_never_prints_the_albums_name() {
        val printed = DestinationInfo(DestinationTitle.Album("Payslips 2026"), count = 3L).toString()
        assertFalse("Payslips" in printed, "the name leaked: $printed")
    }

    private fun album(id: Byte, name: String, count: Int) = album(id, Outcome.Ok(name), count)

    private fun album(id: Byte, name: Outcome<String>, count: Int) =
        dev.ehsanfahad.darkchamber.core.vault.AlbumSummary(
            id = byteArrayOf(id),
            name = name,
            itemCount = count,
            coverItemId = null,
            createdAt = 0L,
        )
}
