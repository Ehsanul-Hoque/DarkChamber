package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ehsanfahad.darkchamber.core.testing.UiTest
import dev.ehsanfahad.darkchamber.core.testing.onButton
import dev.ehsanfahad.darkchamber.core.testing.setThemedContent
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.SelectionDelete
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerDeleteStage
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two delete confirmations — the gallery's for a selection, the viewer's for the file shown — before and
 * while deleting: what they say, which callback each button reaches, and that once a shred is under way
 * nothing on them answers.
 */
class DeleteDialogsUiTest : UiTest() {
    @Test
    fun the_selection_delete_asks_with_the_count_and_each_button_reaches_its_own_callback() =
        runComposeUiTest {
            var confirmed = 0
            var cancelled = 0
            setThemedContent {
                TestGalleryScreen(
                    state = GalleryState(loading = false, deletion = SelectionDelete.Asking(count = 3)),
                    onConfirmDeleteSelection = { confirmed++ },
                    onCancelDeleteSelection = { cancelled++ },
                )
            }

            onNodeWithText(ViewerFormat.deleteQuestion(3)).assertIsDisplayed()
            onNodeWithText(ViewerFormat.deleteExplanation(3)).assertIsDisplayed()
            assertEachButtonReachesItsOwnCallback(
                confirmLabel = ViewerFormat.deleteAction(3),
                confirmed = { confirmed },
                cancelled = { cancelled },
            )
        }

    @Test
    fun the_selection_delete_under_way_answers_nothing_and_says_it_is_deleting() =
        runComposeUiTest {
            var confirmed = 0
            var cancelled = 0
            setThemedContent {
                TestGalleryScreen(
                    state = GalleryState(loading = false, deletion = SelectionDelete.Deleting(count = 3)),
                    onConfirmDeleteSelection = { confirmed++ },
                    onCancelDeleteSelection = { cancelled++ },
                )
            }

            assertSpentWhileDeleting(askedLabel = ViewerFormat.deleteAction(3))
            assertEquals(0 to 0, confirmed to cancelled, "a shred under way cannot be confirmed again or called back")
        }

    @Test
    fun the_viewer_delete_asks_about_this_file_and_each_button_reaches_its_own_callback() =
        runComposeUiTest {
            var confirmed = 0
            var cancelled = 0
            setThemedContent {
                TestViewerScreen(
                    state = ViewerState(loading = false, delete = ViewerDeleteStage.Asking),
                    onConfirmDelete = { confirmed++ },
                    onCancelDelete = { cancelled++ },
                )
            }

            onNodeWithText(ViewerFormat.deleteQuestion(1)).assertIsDisplayed()
            onNodeWithText(ViewerFormat.deleteExplanation(1)).assertIsDisplayed()
            assertEachButtonReachesItsOwnCallback(
                confirmLabel = ViewerFormat.deleteAction(1),
                confirmed = { confirmed },
                cancelled = { cancelled },
            )
        }

    @Test
    fun the_viewer_delete_under_way_answers_nothing_and_says_it_is_deleting() =
        runComposeUiTest {
            var confirmed = 0
            var cancelled = 0
            setThemedContent {
                TestViewerScreen(
                    state = ViewerState(loading = false, delete = ViewerDeleteStage.Deleting),
                    onConfirmDelete = { confirmed++ },
                    onCancelDelete = { cancelled++ },
                )
            }

            assertSpentWhileDeleting(askedLabel = ViewerFormat.deleteAction(1))
            assertEquals(0 to 0, confirmed to cancelled, "a shred under way cannot be confirmed again or called back")
        }

    /** Cancel first, then the action — the dialog stays, since the state is the test's — counting each. */
    private fun ComposeUiTest.assertEachButtonReachesItsOwnCallback(
        confirmLabel: String,
        confirmed: () -> Int,
        cancelled: () -> Int,
    ) {
        onButton(ViewerFormat.cancel()).assertIsEnabled().performClick()
        assertEquals(1 to 0, cancelled() to confirmed(), "Cancel reaches the cancel, and only it")

        onButton(confirmLabel).assertIsEnabled().performClick()
        assertEquals(1 to 1, cancelled() to confirmed(), "the action reaches the confirm, once")
    }

    /** Both buttons there but spent, the action naming what is happening, and taps on either going nowhere. */
    private fun ComposeUiTest.assertSpentWhileDeleting(askedLabel: String) {
        onButton(askedLabel).assertDoesNotExist()
        onButton(ViewerFormat.deleting()).assertIsNotEnabled().performClick()
        onButton(ViewerFormat.cancel()).assertIsNotEnabled().performClick()
    }
}
