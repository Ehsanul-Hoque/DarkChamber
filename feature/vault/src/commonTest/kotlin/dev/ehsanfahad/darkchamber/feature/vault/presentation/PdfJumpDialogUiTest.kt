package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ehsanfahad.darkchamber.core.testing.UiTest
import dev.ehsanfahad.darkchamber.core.testing.onButton
import dev.ehsanfahad.darkchamber.core.testing.setThemedContent
import kotlin.test.Test
import kotlin.test.assertNull

/** The page-jump dialog as drawn: what it refuses, and that a refusal goes nowhere. */
class PdfJumpDialogUiTest : UiTest() {
    @Test
    fun a_page_past_the_last_is_refused_with_the_documents_range_and_goes_nowhere() =
        runComposeUiTest {
            var went: Int? = null
            setThemedContent { PdfJumpDialog(pageCount = 12, onGo = { went = it }, onCancel = {}) }

            onNode(hasSetTextAction()).performTextInput("13")
            onButton(ViewerFormat.jumpGo()).performClick()

            onNodeWithText(ViewerFormat.jumpProblem(PageJump.OutOfRange, 12)!!).assertIsDisplayed()
            assertNull(went, "a page the PDF does not have must not be jumped to")
        }
}
