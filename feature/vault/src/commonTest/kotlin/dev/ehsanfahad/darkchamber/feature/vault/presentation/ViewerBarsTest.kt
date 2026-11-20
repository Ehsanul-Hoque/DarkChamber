package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import dev.ehsanfahad.darkchamber.core.designsystem.darkChamberDarkColors
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bars must stay readable over anything a page can show. Pure white is the worst case (a PDF page),
 * and 4.5:1 is the contrast small text needs (WCAG AA). The viewer is drawn in the dark palette.
 */
class ViewerBarsTest {
    private val colors = darkChamberDarkColors()
    private val overWhite = ViewerBars.scrim.compositeOver(Color.White)

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }

    private fun assertReadable(role: String, text: Color) {
        val ratio = contrast(text, overWhite)
        assertTrue(ratio >= 4.5f, "$role on the bars over a white page: $ratio:1, needs 4.5:1")
    }

    @Test
    fun the_title_and_labels_read_over_a_white_page() = assertReadable("textPrimary", colors.textPrimary)

    /** The item's position ("1 of 3") is the dimmer text: the one a lighter bar loses first. */
    @Test
    fun the_position_reads_over_a_white_page() = assertReadable("textSecondary", colors.textSecondary)

    /** Delete's red is darker than the grey, so it sets how dark the bars must be. */
    @Test
    fun delete_reads_over_a_white_page() = assertReadable("danger", colors.danger)
}
