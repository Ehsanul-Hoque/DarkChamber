package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.graphics.Color

/**
 * The background of the viewer's top and bottom bars: one flat black, not a gradient, so every word
 * and icon on a bar stands on the same darkness whatever is under it. A white PDF page or a photo of a
 * white wall is the worst case; at [SCRIM_ALPHA] the bars' text — primary, secondary and the danger
 * red of Delete — still reads at 4.5:1 or better over pure white. A tap hides the bars to see under them.
 */
internal object ViewerBars {
    const val SCRIM_ALPHA = 0.85f

    /** A media surface, so a raw colour (docs/DESIGN.md §2). */
    val scrim: Color = Color.Black.copy(alpha = SCRIM_ALPHA)
}
