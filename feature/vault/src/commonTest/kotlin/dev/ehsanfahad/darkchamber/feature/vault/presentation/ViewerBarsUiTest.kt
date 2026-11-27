package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.testing.UiTest
import dev.ehsanfahad.darkchamber.core.testing.onButton
import dev.ehsanfahad.darkchamber.core.testing.setThemedContent
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPageSize
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The viewer's bars over a page: there when it opens, with the file's name, its place in the listing and
 * the ways out; gone on a tap and back on the next; and, on a PDF, the page indicator in the bottom bar.
 */
class ViewerBarsUiTest : UiTest() {
    private val shown = item(id = 1, name = "ledger.xyz", mime = "application/octet-stream")
    private val next = item(id = 2, name = "notes.xyz", mime = "application/octet-stream")

    /** The first of two items, on a page with nothing to draw — so the bars are all there is to test. */
    private val opened =
        ViewerState(
            items = listOf(shown, next),
            index = 0,
            pages = mapOf(shown.id.toListKey() to ViewerPage.NotViewable(badge = "XYZ")),
            loading = false,
        )

    @Test
    fun the_bars_are_there_on_open_with_the_name_and_place_and_their_controls_reach_back_and_delete() =
        runComposeUiTest {
            var backs = 0
            var deleteAsked = 0
            setThemedContent {
                TestViewerScreen(state = opened, onBack = { backs++ }, onRequestDelete = { deleteAsked++ })
            }

            onNodeWithText(ViewerFormat.title(shown.meta)).assertIsDisplayed()
            onNodeWithText(ViewerFormat.position(index = 0, count = 2)).assertIsDisplayed()

            onNodeWithContentDescription(ViewerFormat.back()).performClick()
            assertEquals(1 to 0, backs to deleteAsked, "Back leaves, and only that")

            onButton(ViewerFormat.deleteAction(1)).performClick()
            assertEquals(1 to 1, backs to deleteAsked, "the bar's Delete asks for the delete, once")
        }

    @Test
    fun a_tap_on_the_page_hides_the_bars_and_the_next_tap_brings_them_back() =
        runComposeUiTest {
            setThemedContent { TestViewerScreen(state = opened) }
            val page = onNodeWithText(ViewerFormat.pageMessage(ViewerPage.NotViewable(badge = "XYZ"))!!)

            page.performClick()
            onNodeWithText(ViewerFormat.title(shown.meta)).assertDoesNotExist()
            onNodeWithContentDescription(ViewerFormat.back()).assertDoesNotExist()
            onButton(ViewerFormat.deleteAction(1)).assertDoesNotExist()

            page.performClick()
            onNodeWithText(ViewerFormat.title(shown.meta)).assertIsDisplayed()
            onButton(ViewerFormat.deleteAction(1)).assertIsDisplayed()
        }

    @Test
    fun a_pdfs_page_indicator_sits_in_the_bottom_bar_and_opens_the_page_jump() =
        runComposeUiTest {
            val pdf = item(id = 3, name = "manual.pdf", mime = "application/pdf")
            val page =
                ViewerPage.Pdf(
                    pageCount = 12,
                    pageSizes = List(12) { PdfPageSize(widthPt = 612, heightPt = 792) },
                )
            val slot = ViewerFormat.pdfIndicator(page, pdf.meta, reading = 0, loadingMs = 0L) as IndicatorSlot.Shown
            setThemedContent {
                TestViewerScreen(
                    state =
                        ViewerState(
                            items = listOf(pdf),
                            index = 0,
                            pages = mapOf(pdf.id.toListKey() to page),
                            loading = false,
                        ),
                )
            }

            onNodeWithContentDescription(slot.indicator.spoken).assertIsDisplayed().performClick()

            onButton(ViewerFormat.jumpGo()).assertIsDisplayed()
        }

    private fun item(
        id: Byte,
        name: String,
        mime: String,
    ) = GalleryItem(
        id = byteArrayOf(id),
        kind = VaultItemKind.FILE,
        size = 1_024L,
        createdAt = 0L,
        meta = GalleryItemMeta.Readable(name, mime),
    )
}
