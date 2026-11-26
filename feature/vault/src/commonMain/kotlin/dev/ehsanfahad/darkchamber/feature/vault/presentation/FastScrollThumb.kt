package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.core.designsystem.prefersReducedMotion

/**
 * The fast-scroll thumb at [topPx] on the right edge, and while it is [dragging] where it has reached
 * ([bubble]) beside it — a PDF's page or a text file's line; none on a page with nothing to name it by
 * (a formatted Markdown file). Drawing only — the column's gesture
 * handler takes the touches — and nothing a screen reader lands on: the column's own scroll does the
 * same work there.
 */
@Composable
internal fun BoxWithConstraintsScope.FastScrollThumb(shown: Boolean, topPx: Int, dragging: Boolean, bubble: String?) {
    val colors = DarkChamberTheme.colors
    val density = LocalDensity.current
    val duration = if (prefersReducedMotion()) 0 else DarkChamberTheme.motion.motionBase
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(duration), label = "thumb")
    if (alpha == 0f) return
    val marginPx = with(density) { THUMB_MARGIN.roundToPx() }
    val thumbWidthPx = with(density) { THUMB_WIDTH.roundToPx() }
    val thumbHeightPx = with(density) { THUMB_HEIGHT.roundToPx() }

    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset { IntOffset(-marginPx, topPx) }
            .size(THUMB_WIDTH, THUMB_HEIGHT)
            .graphicsLayer { this.alpha = alpha }
            // Dark with a light edge: it shows over a white page and over the black between pages.
            .background(ViewerBars.scrim, DarkChamberTheme.shapes.small)
            .border(1.dp, colors.textSecondary, DarkChamberTheme.shapes.small)
            .clearAndSetSemantics {},
    )
    if (dragging && bubble != null) {
        var bubbleHeight by remember { mutableIntStateOf(0) }
        Text(
            text = bubble,
            style = DarkChamberTheme.typography.label,
            color = colors.textPrimary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(-(marginPx + thumbWidthPx + marginPx), topPx + thumbHeightPx / 2 - bubbleHeight / 2) }
                .onSizeChanged { bubbleHeight = it.height }
                .background(ViewerBars.scrim, DarkChamberTheme.shapes.medium)
                .padding(horizontal = DarkChamberTheme.spacing.spaceMd, vertical = DarkChamberTheme.spacing.spaceXs)
                .clearAndSetSemantics {},
        )
    }
}

/** The fast-scroll thumb: what is drawn, how far in from the edge, and what a finger can take. */
internal val THUMB_WIDTH = 8.dp
internal val THUMB_HEIGHT = 56.dp
internal val THUMB_MARGIN = 8.dp
internal val THUMB_STRIP = 48.dp
internal val THUMB_SLOP = 8.dp

/** How long the thumb stays after the column last moved. */
internal const val THUMB_LINGER_MS = 1_500L
