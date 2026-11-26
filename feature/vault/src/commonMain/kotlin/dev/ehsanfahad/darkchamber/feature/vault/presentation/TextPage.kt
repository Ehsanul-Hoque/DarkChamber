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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.feature.vault.api.TextDocument
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
                font = if (lines?.font == TextFont.Mono) DarkChamberTheme.typography.mono else DarkChamberTheme.typography.bodyLarge,
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
    font: TextStyle,
    note: String?,
    onTap: () -> Unit,
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    barsShown: Boolean,
) {
    val colors = DarkChamberTheme.colors
    // Every row continues the text-line spacing: two stored lines sit as far apart as two wrapped ones.
    val style = remember(font) { TextReading.spaced(font, first = false, last = false) }
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
        thumbWhenScrolls = false,
        estimate = rememberRowEstimate(rows, style),
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
 * is one and the fast-scroll thumb on a long column ([TextReading.worthAThumb]; [thumbWhenScrolls] for
 * formatted Markdown) — placed by the rows' measured heights ([RowHeights]) and kept still while those
 * estimates are corrected ([ThumbPlace]), naming the row reached with [bubble] while dragged (no bubble where
 * it answers `null`). With [estimate] — every row's height for a width its text is laid out in, after a given
 * number of empty places for the items above the rows, or `null` when it cannot tell — rows not laid out yet
 * count as their own estimate rather than the average.
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
    thumbWhenScrolls: Boolean,
    estimate: (suspend (textWidthPx: Int, leading: Int) -> IntArray?)? = null,
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
        // Every row's own estimate once worked out (see ScreenLines); until then, and where there is none, the
        // average stands in. A new estimate (another font or size) starts over.
        var estimated by remember(itemCount, widthPx, estimate) { mutableStateOf<RowHeights?>(null) }
        if (estimate != null) {
            val layoutDirection = LocalLayoutDirection.current
            val insets = WindowInsets.safeDrawing.let { it.getLeft(density, layoutDirection) + it.getRight(density, layoutDirection) }
            // The rows' own width: the column's, less the side insets and the content padding either side.
            val textWidthPx = widthPx - insets - with(density) { (DarkChamberTheme.spacing.spaceMd * 2 + THUMB_WIDTH).roundToPx() }
            LaunchedEffect(itemCount, textWidthPx, estimate) {
                // Built with the note's place already in it, so the column takes the array as it is.
                val all = estimate(textWidthPx, header) ?: return@LaunchedEffect
                if (all.size == itemCount) estimated = RowHeights.estimated(all)
            }
        }
        // The averaged heights are kept only while there is no estimate: once one lands they are let go.
        // A new density (the font size setting) lays every row out again at another height, as a new width does.
        val heights = estimated.let { if (it != null) it else remember(itemCount, widthPx, density) { RowHeights(itemCount) } }
        var measured by remember(heights) { mutableIntStateOf(0) }
        // Where a shown thumb was pinned when an estimate last changed under it (see ThumbPlace).
        var anchor by remember(heights) { mutableStateOf<ThumbAnchor?>(null) }
        // How far down the column is, and how far it can go, by the heights known at the time of asking.
        fun scrolledNow(): Float = heights.top(list.firstVisibleItemIndex) + list.firstVisibleItemScrollOffset
        fun maxScrollNow(): Float = list.layoutInfo.let {
            (heights.total() + it.beforeContentPadding + it.afterContentPadding - it.viewportSize.height).coerceAtLeast(0f)
        }
        // The fraction the thumb stands at, as it is drawn.
        fun fractionNow(): Float = when {
            dragging -> dragFraction
            !list.canScrollForward -> 1f
            else -> ThumbPlace.fraction(scrolledNow(), maxScrollNow(), anchor)
        }
        var thumbShownLately by remember { mutableStateOf(false) }
        LaunchedEffect(list, heights) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.index to it.size } }.collect { seen ->
                // The thumb's place by the old estimate, so that a corrected one cannot move it.
                val shownBefore = fractionNow()
                val scrolledBefore = scrolledNow()
                val maxBefore = maxScrollNow()
                seen.forEach { (index, size) -> heights.record(index, size) }
                val scrolledAfter = scrolledNow()
                if (scrolledAfter != scrolledBefore || maxScrollNow() != maxBefore) {
                    anchor = ThumbPlace.repin(thumbShownLately, shownBefore, scrolledAfter)
                }
                measured++
            }
        }

        val info = list.layoutInfo
        // Read so the figures below follow each new height recorded.
        val total = measured.let { heights.total() }
        val maxScroll = measured.let { maxScrollNow() }
        val fraction = measured.let { fractionNow() }
        val firstRow = (list.firstVisibleItemIndex - header).coerceAtLeast(0)
        val thumbTop = FastScroll.thumbTop(fraction, trackTop, trackBottom, thumbHeightPx)
        val worthNow = TextReading.worthAThumb(
            whenScrolls = thumbWhenScrolls,
            canScroll = list.canScrollForward || list.canScrollBackward,
            contentHeight = total.toInt(),
            viewportHeight = info.viewportSize.height,
        )
        val hasThumb = TextReading.keepsThumb(thumbKept, worthNow, dragging)
        LaunchedEffect(hasThumb) { if (hasThumb) thumbKept = true }
        val thumbShown = FastScroll.visible(
            worthIt = hasThumb,
            zoomedIn = false,
            barsShown = barsShown,
            scrolledLately = scrolledLately,
            dragging = dragging,
        )
        // A hidden thumb keeps no pin: its true place is taken up unseen, ready for the next time it shows.
        LaunchedEffect(thumbShown) {
            thumbShownLately = thumbShown
            if (!thumbShown) anchor = null
        }
        val reachedRow = if (dragging) heights.at(ThumbPlace.scrolledAt(dragFraction, maxScroll, anchor)).index - header else firstRow
        val reached = if (rowCount == 0) null else bubble(reachedRow.coerceIn(0, rowCount - 1))
        // Read by the gesture handler, which outlives a composition.
        val grab by rememberUpdatedState(Triple(thumbShown, thumbTop, fraction))
        val track by rememberUpdatedState(Triple(trackTop, trackBottom, maxScroll))
        val pinned by rememberUpdatedState(anchor)
        // The heights too: the estimate can land after the gesture handler started, and the averaged
        // heights it would otherwise keep are let go and no longer recorded.
        val heightsNow by rememberUpdatedState(heights)

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
                                    val spot = heightsNow.at(ThumbPlace.scrolledAt(next, scrollable, pinned))
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

/**
 * Every one of [rows]' heights in [style] for a given text width, after [leading] empty places: each
 * distinct character measured once, in a single layout, then each row wrapped over those widths
 * ([ScreenLines]). `null` when the measuring cannot be trusted. The scan and the wrapping run off the main
 * thread and stop when the page goes (a lock, a swipe, a new width); the measuring runs where it is called —
 * the composition's own coroutine, on the main thread — because the font machinery it uses is the screen's,
 * which is not to be shared with another thread. The widths come from the file's own characters, so they live
 * only in this work and the heights it hands back.
 */
@Composable
private fun rememberRowEstimate(rows: TextRows, style: TextStyle): suspend (textWidthPx: Int, leading: Int) -> IntArray? {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    return remember(rows, style, measurer) {
        { textWidthPx, leading ->
            val codes = withContext(Dispatchers.Default) {
                ScreenLines.measurable(ScreenLines.distinct(rows.whole) { ensureActive() })
            }
            // Each character between two bars, one per line, so a space is measured too (a space ending a
            // line would hang and measure nothing); the bars alone are line 0.
            val probe = buildString {
                append(BAR).append(BAR)
                codes.forEach { append('\n').append(BAR).append(ScreenLines.text(it)).append(BAR) }
            }
            val layout = measurer.measure(probe, style, softWrap = false)
            if (layout.lineCount != codes.size + 1) {
                null
            } else {
                fun lineWidth(line: Int) = layout.getLineRight(line) - layout.getLineLeft(line)
                val bars = lineWidth(0)
                val widths = LetterWidths(codes, FloatArray(codes.size) { (lineWidth(it + 1) - bars).coerceAtLeast(0f) })
                val firstLine = measurer.measure("A", style).size.height
                val nextLine = measurer.measure("A\nA", style).size.height - firstLine
                withContext(Dispatchers.Default) {
                    ScreenLines.heights(rows, textWidthPx.toFloat(), widths, firstLine, nextLine, leading) { ensureActive() }
                }
            }
        }
    }
}

/** What each measured character sits between. */
private const val BAR = '|'
