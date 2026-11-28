package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ehsanfahad.darkchamber.core.testing.UiTest
import dev.ehsanfahad.darkchamber.core.testing.onButton
import dev.ehsanfahad.darkchamber.core.testing.setThemedContent
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraft
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraftProblem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The album-name dialog, one interaction from a refused name to the write: the reason under the field, each
 * control reaching its own callback, and — once the write runs — nothing left that answers.
 *
 * The confirm labels are the caller's to choose (create and rename pass their own), so the test hands in its
 * own; what the dialog decides is which of the two it shows.
 */
class AlbumNameDialogUiTest : UiTest() {
    @Test
    fun a_refused_name_says_why_each_control_reaches_its_callback_and_the_write_silences_the_dialog() =
        runComposeUiTest {
            var draft by mutableStateOf(AlbumDraft(name = "Trips", problem = AlbumDraftProblem.CreateFailed))
            var typed: String? = null
            var confirmed = 0
            var cancelled = 0
            setThemedContent {
                AlbumNameDialog(
                    title = GalleryFormat.renameAlbum(),
                    draft = draft,
                    confirmLabel = CONFIRM,
                    savingLabel = SAVING,
                    onNameChange = { typed = it },
                    onConfirm = { confirmed++ },
                    onCancel = { cancelled++ },
                )
            }

            onNodeWithText(GalleryFormat.albumDraftProblem(AlbumDraftProblem.CreateFailed)).assertIsDisplayed()
            // Found by its label: a disabled field drops its set-text action, so that cannot find it later.
            val field = onNodeWithText(GalleryFormat.albumNameLabel())
            field.assertIsEnabled().performTextReplacement("Holiday")
            assertEquals("Holiday", typed, "what is typed goes to the draft, not into the dialog's own memory")
            onButton(ViewerFormat.cancel()).assertIsEnabled().performClick()
            onButton(CONFIRM).assertIsEnabled().performClick()
            assertEquals(1 to 1, cancelled to confirmed, "Cancel and the confirm each reach their own callback, once")

            draft = AlbumDraft(name = "Holiday", saving = true)

            onButton(CONFIRM).assertDoesNotExist()
            onButton(SAVING).assertIsNotEnabled().performClick()
            onButton(ViewerFormat.cancel()).assertIsNotEnabled().performClick()
            field.assertIsNotEnabled()
            assertEquals(1 to 1, cancelled to confirmed, "while the write runs, no tap reaches anything")
        }

    private companion object {
        const val CONFIRM = "Confirm label"
        const val SAVING = "Saving label"
    }
}
