package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.feature.vault.api.TextDocument
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

/**
 * A text file: one wrapped row per item of a lazy column — a line, or a piece of one too long for a
 * single item ([TextReading.rows]) — in the reading font or monospace
 * ([TextReading.view]), with the fast-scroll thumb on a long file. Left and right still turn the
 * viewer's page; a tap shows and hides the bars. Nothing is selectable — a selection would offer copy,
 * and the clipboard is outside the vault.
 *
 * A Markdown file chosen formatted is drawn here as its lines until its blocks have a page of their own.
 */
@Composable
internal fun TextPageView(
    page: ViewerPage.Text,
    document: TextDocument,
    formatted: Boolean,
    onTap: () -> Unit,
    /** Room above the first line and below the last, so neither ends under a bar. */
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    /** Whether the viewer's bars are on screen: the fast-scroll thumb shows with them. */
    barsShown: Boolean,
) {
    val view = TextReading.view(page.kind, canFormat = document.formatted != null, formatted = formatted)
    val (lines, font) = when (view) {
        is TextView.Lines ->
            (if (view.indented) (document.formatted as? FormattedText.Json)?.lines ?: document.plain else document.plain) to view.font
        TextView.Markdown -> document.plain to TextFont.Reading
    }
    // A new view starts at its top: its lines are not the other view's lines.
    key(view) {
        TextLines(
            lines = lines,
            style = if (font == TextFont.Mono) DarkChamberTheme.typography.mono else DarkChamberTheme.typography.bodyLarge,
            note = page.note?.let(ViewerFormat::formatNote),
            onTap = onTap,
            topPaddingPx = topPaddingPx,
            bottomPaddingPx = bottomPaddingPx,
            barsShown = barsShown,
        )
    }
}

@Composable
private fun TextLines(
    lines: DecodedText.Text,
    style: TextStyle,
    note: String?,
    onTap: () -> Unit,
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    barsShown: Boolean,
) {
    val colors = DarkChamberTheme.colors
    val list = rememberLazyListState()
    // The note, when there is one, is the column's first item; rows are counted after it.
    val header = if (note != null) 1 else 0
    // A line too long to lay out as one item is cut into rows (the minified-file case).
    val rows = remember(lines) { TextReading.rows(lines) }
    val rowCount = rows.count

    var scrolledLately by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .drop(1)
            .collectLatest {
                scrolledLately = true
                delay(THUMB_LINGER_MS)
                scrolledLately = false
            }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val heightPx = constraints.maxHeight
        val widthPx = constraints.maxWidth
        val density = LocalDensity.current
        val thumbHeightPx = with(density) { THUMB_HEIGHT.roundToPx() }
        val stripPx = with(density) { THUMB_STRIP.roundToPx() }
        val slopPx = with(density) { THUMB_SLOP.roundToPx() }
        val trackTop = topPaddingPx
        val trackBottom = heightPx - bottomPaddingPx

        val info = list.layoutInfo
        val visibleRows = info.visibleItemsInfo.count { it.index >= header }
        val firstItem = info.visibleItemsInfo.firstOrNull()
        val firstRow = (list.firstVisibleItemIndex - header).coerceAtLeast(0)
        val withinFirst = if (firstItem != null && firstItem.size > 0 && list.firstVisibleItemIndex >= header) {
            list.firstVisibleItemScrollOffset.toFloat() / firstItem.size
        } else {
            0f
        }
        val fraction = if (dragging) {
            dragFraction
        } else {
            TextReading.fraction(firstRow, withinFirst, rowCount, visibleRows, atEnd = !list.canScrollForward)
        }
        val thumbTop = PdfFastScroll.thumbTop(fraction, trackTop, trackBottom, thumbHeightPx)
        val thumbShown = PdfFastScroll.visible(
            worthIt = TextReading.worthIt(rowCount, visibleRows),
            zoomedIn = false,
            barsShown = barsShown,
            scrolledLately = scrolledLately,
            dragging = dragging,
        )
        // The bubble names the stored line, whichever of its rows the drag has reached.
        val reachedRow = if (dragging) TextReading.rowAt(dragFraction, rowCount, visibleRows) else firstRow
        val reached = if (rowCount == 0) 0 else rows.lineOf(reachedRow.coerceIn(0, rowCount - 1))
        // Read by the gesture handler, which outlives a composition.
        val grab by rememberUpdatedState(Triple(thumbShown, thumbTop, fraction))
        val track by rememberUpdatedState(Triple(trackTop, trackBottom, visibleRows))

        LazyColumn(
            state = list,
            contentPadding = with(density) {
                PaddingValues(
                    top = topPaddingPx.toDp() + DarkChamberTheme.spacing.spaceXs,
                    bottom = bottomPaddingPx.toDp() + DarkChamberTheme.spacing.spaceXs,
                    start = DarkChamberTheme.spacing.spaceMd,
                    // Clear of the thumb, so it never sits over the ends of lines.
                    end = DarkChamberTheme.spacing.spaceMd + THUMB_WIDTH,
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .pointerInput(stripPx, thumbHeightPx, slopPx, widthPx, rowCount, header) {
                    // A press on the thumb is the thumb's whole gesture; any other is the column's and the pager's.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val (shownNow, topNow, fractionNow) = grab
                        val grabbed = shownNow &&
                            PdfFastScroll.grabs(down.position.x, down.position.y, size.width, stripPx, topNow, thumbHeightPx, slopPx)
                        if (!grabbed) return@awaitEachGesture
                        down.consume()
                        val hold = down.position.y - topNow
                        dragFraction = fractionNow
                        dragging = true
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val finger = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!finger.pressed) break
                                val (top, bottom, onScreen) = track
                                val next = PdfFastScroll.fractionAt((finger.position.y - hold).roundToInt(), top, bottom, thumbHeightPx)
                                dragFraction = next
                                list.requestScrollToItem(header + TextReading.rowAt(next, rowCount, onScreen))
                                event.changes.forEach { it.consume() }
                            }
                        } finally {
                            dragging = false
                        }
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        ) {
            if (note != null) {
                item(key = "note") {
                    Text(
                        text = note,
                        style = DarkChamberTheme.typography.label,
                        color = colors.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(bottom = DarkChamberTheme.spacing.spaceSm),
                    )
                }
            }
            items(count = rowCount) { index ->
                val row = rows.row(index)
                val spoken = TextReading.spoken(row)
                Text(
                    text = row,
                    style = style,
                    color = colors.textPrimary,
                    // Each row its own stop for a screen reader; a blank one is passed over.
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                        if (spoken != null) contentDescription = spoken
                    },
                )
            }
        }

        FastScrollThumb(
            shown = thumbShown,
            topPx = thumbTop,
            dragging = dragging,
            bubble = ViewerFormat.fastScrollLine(reached),
        )
    }
}
