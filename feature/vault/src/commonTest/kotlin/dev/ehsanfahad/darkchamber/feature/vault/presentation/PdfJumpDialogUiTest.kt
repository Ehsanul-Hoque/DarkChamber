package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ehsanfahad.darkchamber.core.testing.UiTest
import dev.ehsanfahad.darkchamber.core.testing.onButton
import dev.ehsanfahad.darkchamber.core.testing.setThemedContent
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun anything_but_digits_is_refused_typing_clears_the_reason_and_a_page_it_has_goes_there_from_zero() =
        runComposeUiTest {
            var went: Int? = null
            setThemedContent { PdfJumpDialog(pageCount = 12, onGo = { went = it }, onCancel = {}) }
            val field = onNode(hasSetTextAction())
            val notANumber = ViewerFormat.jumpProblem(PageJump.NotANumber, 12)!!

            field.performTextInput("7a")
            onButton(ViewerFormat.jumpGo()).performClick()
            onNodeWithText(notANumber).assertIsDisplayed()
            assertNull(went, "a refused entry goes nowhere")

            field.performTextReplacement("7")
            onNodeWithText(notANumber).assertDoesNotExist()
            onButton(ViewerFormat.jumpGo()).performClick()

            assertEquals(6, went, "page 7 is the page at index 6")
        }
}
