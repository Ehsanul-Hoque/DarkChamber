package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.testing.UiTest
import dev.ehsanfahad.darkchamber.core.testing.assertNoText
import dev.ehsanfahad.darkchamber.core.testing.onButton
import dev.ehsanfahad.darkchamber.core.testing.setThemedContent
import dev.ehsanfahad.darkchamber.feature.vault.api.FailedImport
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The gallery as drawn when there is nothing to list, and when a batch has ended: what the screen says,
 * what it must not say, and which callback each of the summary's controls reaches.
 */
class GalleryListingUiTest : UiTest() {
    private val emptyVault = GalleryFormat.emptyListing(GalleryScope.WholeVault, unreadable = false)
    private val unreadable = GalleryFormat.emptyListing(GalleryScope.WholeVault, unreadable = true)

    @Test
    fun an_empty_vault_says_so_and_does_not_claim_a_failed_read() =
        runComposeUiTest {
            setThemedContent { TestGalleryScreen(state = GalleryState(loading = false)) }

            onNodeWithText(emptyVault).assertIsDisplayed()
            assertNoText(unreadable)
        }

    @Test
    fun a_listing_that_could_not_be_read_says_so_after_the_banner_is_dismissed_and_never_calls_the_vault_empty() =
        runComposeUiTest {
            // The banner's error already acknowledged: only the lasting fact is left to tell the two apart.
            setThemedContent {
                TestGalleryScreen(state = GalleryState(loading = false, listingUnreadable = true, error = null))
            }

            onNodeWithText(unreadable).assertIsDisplayed()
            assertNoText(emptyVault)
        }

    @Test
    fun the_summary_says_what_was_added_and_what_was_not_and_done_only_acknowledges_it() =
        runComposeUiTest {
            var acknowledged = 0
            var deleteAsked = 0
            val failed =
                listOf(
                    FailedImport("a.jpg", VaultError.Crypto.IntegrityFailure),
                    FailedImport("b.pdf", VaultError.Crypto.IntegrityFailure),
                )
            setThemedContent {
                TestGalleryScreen(
                    state = finished(imported = 3, failed = failed, deletableOriginals = 2),
                    onAcknowledgeImport = { acknowledged++ },
                    onRequestOriginalDelete = { deleteAsked++ },
                )
            }

            onNodeWithText(GalleryFormat.importedCount(3)).assertIsDisplayed()
            onNodeWithText(GalleryFormat.failedCount(2)).assertIsDisplayed()
            onButton(GalleryFormat.done()).performClick()

            assertEquals(1, acknowledged, "Done acknowledges the batch, once")
            assertEquals(0, deleteAsked, "the safe action must never reach the delete")
        }

    @Test
    fun deleting_the_originals_asks_for_the_delete_and_does_not_dismiss_the_summary() =
        runComposeUiTest {
            var acknowledged = 0
            var deleteAsked = 0
            setThemedContent {
                TestGalleryScreen(
                    state = finished(imported = 2, deletableOriginals = 2),
                    onAcknowledgeImport = { acknowledged++ },
                    onRequestOriginalDelete = { deleteAsked++ },
                )
            }

            onButton(GalleryFormat.deleteOriginals(2)).performClick()

            assertEquals(1, deleteAsked, "the offer opens the disclosure, once")
            assertEquals(0, acknowledged, "asking to delete is not dismissing the summary")
        }

    @Test
    fun a_clean_batch_with_nothing_deletable_shows_no_failure_line_and_no_delete_offer() =
        runComposeUiTest {
            setThemedContent { TestGalleryScreen(state = finished(imported = 4, deletableOriginals = 0)) }

            onNodeWithText(GalleryFormat.importedCount(4)).assertIsDisplayed()
            onButton(GalleryFormat.done()).assertIsDisplayed()
            // What the screen would draw if it showed either line regardless of the batch.
            assertNoText(GalleryFormat.failedCount(0))
            assertNoText(GalleryFormat.deleteOriginals(0))
        }

    private fun finished(
        imported: Int,
        failed: List<FailedImport> = emptyList(),
        deletableOriginals: Int,
    ) = GalleryState(
        loading = false,
        batch = ImportState.Finished(imported = imported, failed = failed, deletableOriginals = deletableOriginals),
    )
}
