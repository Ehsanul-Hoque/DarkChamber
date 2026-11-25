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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
 * A text file: its lines as a column of wrapped rows in the reading font or monospace
 * ([TextReading.view]), or — Markdown chosen formatted — its blocks ([MarkdownPageView]); either way
 * with the fast-scroll thumb on a long file. Left and right still turn the viewer's page; a tap shows
 * and hides the bars. Nothing is selectable — a selection would offer copy, and the clipboard is
 * outside the vault.
 */
@Composable
internal fun TextPageView(
    page: ViewerPage.Text,
    document: TextDocument,
    formatted: Boolean,
    onTap: () -> Unit,
    /** Room above the first row and below the last, so neither ends under a bar. */
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    /** Whether the viewer's bars are on screen: the fast-scroll thumb shows with them. */
    barsShown: Boolean,
    /** A web address the reader confirmed opening, for the viewer to hand over. */
    onOpenLink: (String) -> Unit,
) {
    val view = TextReading.view(page.kind, canFormat = document.formatted != null, formatted = formatted)
    // A new view starts at its top: its rows are not the other view's rows.
    key(view) {
        val markdown = (document.formatted as? FormattedText.Markdown)?.document
        if (view == TextView.Markdown && markdown != null) {
            MarkdownPageView(
                document = markdown,
                onTap = onTap,
                topPaddingPx = topPaddingPx,
                bottomPaddingPx = bottomPaddingPx,
                barsShown = barsShown,
                onOpenLink = onOpenLink,
            )
        } else {
            val lines = view as? TextView.Lines
            val shown = if (lines?.indented == true) (document.formatted as? FormattedText.Json)?.lines ?: document.plain else document.plain
            TextLines(
                lines = shown,
                style = if (lines?.font == TextFont.Mono) DarkChamberTheme.typography.mono else DarkChamberTheme.typography.bodyLarge,
                note = page.note?.let(ViewerFormat::formatNote),
                onTap = onTap,
                topPaddingPx = topPaddingPx,
                bottomPaddingPx = bottomPaddingPx,
                barsShown = barsShown,
            )
        }
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
    // A line too long to lay out as one item is cut into rows (the minified-file case).
    val rows = remember(lines) { TextReading.rows(lines) }
    RowsColumn(
        list = rememberLazyListState(),
        rowCount = rows.count,
        note = note,
        // The bubble names the stored line, whichever of its rows the drag has reached.
        bubble = { row -> ViewerFormat.fastScrollLine(rows.lineOf(row)) },
        onTap = onTap,
        topPaddingPx = topPaddingPx,
        bottomPaddingPx = bottomPaddingPx,
        barsShown = barsShown,
    ) { index ->
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

/**
 * A lazy column of [rowCount] rows, drawn by [row], under the bars, with a [note] above them when there
 * is one and the fast-scroll thumb on a long column — placed by the rows' measured heights
 * ([RowHeights]), naming the row reached with [bubble] while dragged (no bubble where it answers `null`).
 */
@Composable
internal fun RowsColumn(
    list: LazyListState,
    rowCount: Int,
    note: String?,
    bubble: (row: Int) -> String?,
    onTap: () -> Unit,
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    barsShown: Boolean,
    row: @Composable (index: Int) -> Unit,
) {
    val colors = DarkChamberTheme.colors
    // The note, when there is one, is the column's first item; rows are counted after it.
    val header = if (note != null) 1 else 0

    var scrolledLately by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // Once the column has been long enough for the thumb, it keeps it (see TextReading.keepsThumb).
    var thumbKept by remember { mutableStateOf(false) }
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

        // Every item's height as it is laid out, and from them the column's height (see RowHeights). Kept
        // for this width: a new width lays every item out again at another height.
        val itemCount = header + rowCount
        val heights = remember(itemCount, widthPx) { RowHeights(itemCount) }
        var measured by remember(heights) { mutableIntStateOf(0) }
        LaunchedEffect(list, heights) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.index to it.size } }.collect { seen ->
                seen.forEach { (index, size) -> heights.record(index, size) }
                measured++
            }
        }

        val info = list.layoutInfo
        // Read so the figures below follow each new height recorded.
        val total = measured.let { heights.total() }
        val maxScroll = (total + info.beforeContentPadding + info.afterContentPadding - info.viewportSize.height).coerceAtLeast(0f)
        val scrolled = heights.top(list.firstVisibleItemIndex) + list.firstVisibleItemScrollOffset
        val fraction = when {
            dragging -> dragFraction
            !list.canScrollForward -> 1f
            maxScroll <= 0f -> 0f
            else -> (scrolled / maxScroll).coerceIn(0f, 1f)
        }
        val firstRow = (list.firstVisibleItemIndex - header).coerceAtLeast(0)
        val thumbTop = FastScroll.thumbTop(fraction, trackTop, trackBottom, thumbHeightPx)
        val worthNow = FastScroll.worthIt(total.toInt(), info.viewportSize.height)
        val hasThumb = TextReading.keepsThumb(thumbKept, worthNow, dragging)
        LaunchedEffect(hasThumb) { if (hasThumb) thumbKept = true }
        val thumbShown = FastScroll.visible(
            worthIt = hasThumb,
            zoomedIn = false,
            barsShown = barsShown,
            scrolledLately = scrolledLately,
            dragging = dragging,
        )
        val reachedRow = if (dragging) heights.at(dragFraction * maxScroll).index - header else firstRow
        val reached = if (rowCount == 0) null else bubble(reachedRow.coerceIn(0, rowCount - 1))
        // Read by the gesture handler, which outlives a composition.
        val grab by rememberUpdatedState(Triple(thumbShown, thumbTop, fraction))
        val track by rememberUpdatedState(Triple(trackTop, trackBottom, maxScroll))

        LazyColumn(
            state = list,
            contentPadding = with(density) {
                PaddingValues(
                    top = topPaddingPx.toDp() + DarkChamberTheme.spacing.spaceXs,
                    bottom = bottomPaddingPx.toDp() + DarkChamberTheme.spacing.spaceXs,
                    start = DarkChamberTheme.spacing.spaceMd,
                    // Clear of the thumb, so it never sits over the ends of rows.
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
                            FastScroll.grabs(down.position.x, down.position.y, size.width, stripPx, topNow, thumbHeightPx, slopPx)
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
                                val (top, bottom, scrollable) = track
                                val next = FastScroll.fractionAt((finger.position.y - hold).roundToInt(), top, bottom, thumbHeightPx)
                                dragFraction = next
                                if (TextReading.toTheEnd(next) && itemCount > 0) {
                                    // Past the last row's top to its end: the list stops at the end of its content.
                                    list.requestScrollToItem(itemCount - 1, Int.MAX_VALUE)
                                } else {
                                    val spot = heights.at(next * scrollable)
                                    list.requestScrollToItem(spot.index, spot.offsetPx)
                                }
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
            items(count = rowCount) { index -> row(index) }
        }

        FastScrollThumb(shown = thumbShown, topPx = thumbTop, dragging = dragging, bubble = reached)
    }
}
