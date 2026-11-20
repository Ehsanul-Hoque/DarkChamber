package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A PDF's pages, stacked and fitted to the width, scrolling up and down — left and right still turn
 * the viewer's page. Each page has its slot at its own shape before it is drawn, so the column never
 * jumps as pictures arrive. Pinch and double-tap zoom the whole column ([ViewerZoom]'s rules); zoomed
 * in, a drag moves across the page and, past its edge, on through the pages ([PdfReading.panThenScroll]).
 *
 * It tells the viewer what it shows — the pages seen, at what width and zoom — and the viewer draws the
 * pages near them into [drawn]: absent until drawn, `null` for a page that would not draw.
 */
@Composable
internal fun PdfPageView(
    page: ViewerPage.Pdf,
    drawn: Map<Int, ImageBitmap?>,
    isShown: Boolean,
    onTap: () -> Unit,
    onZoomedIn: (Boolean) -> Unit,
    onViewport: (visible: IntRange, shownWidthPx: Int, zoom: Float, screenWidthPx: Int) -> Unit,
    onScrolled: (page: Int, offsetPx: Int) -> Unit,
    /** The page most on screen, for the indicator. */
    onReading: (Int) -> Unit,
    /** Room above the first page and below the last, so neither ends under a bar. */
    topPaddingPx: Int = 0,
    bottomPaddingPx: Int = 0,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // The zoom pages are drawn for: taken when a gesture ends, so a pinch does not redraw every frame.
    var drawZoom by remember { mutableFloatStateOf(1f) }
    val list = rememberLazyListState(page.start.page, page.start.offsetPx)
    val shown by rememberUpdatedState(isShown)

    LaunchedEffect(isShown) {
        if (!isShown) {
            zoom = 1f
            offset = Offset.Zero
            drawZoom = 1f
        }
    }
    LaunchedEffect(zoom) { onZoomedIn(!ViewerZoom.pagerMaySwipe(zoom)) }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { (index, offsetPx) -> if (shown) onScrolled(index, offsetPx) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        // The column fills the box, so it is what is zoomed and what overhangs.
        val box = Size(widthPx.toFloat(), heightPx.toFloat())
        val center = Offset(box.width / 2f, box.height / 2f)
        val density = LocalDensity.current
        val heights = remember(page.pageSizes, widthPx) {
            page.pageSizes.map { PdfReading.pageHeight(widthPx, it.widthPt, it.heightPt) }
        }

        LaunchedEffect(list, widthPx, heightPx, isShown) {
            if (!isShown) return@LaunchedEffect
            snapshotFlow {
                val band = PdfReading.seenBand(heightPx, zoom, offset.y)
                // The list counts from the end of its top padding; the band counts from the box's top.
                val before = -list.layoutInfo.viewportStartOffset
                val laidOut = list.layoutInfo.visibleItemsInfo.map { LaidOutPage(it.index, it.offset + before, it.size) }
                Triple(
                    PdfReading.pagesSeen(laidOut, band.top, band.bottom),
                    PdfReading.mostOnScreen(laidOut, band.top, band.bottom),
                    drawZoom,
                )
            }.distinctUntilChanged().collect { (seen, most, atZoom) ->
                onViewport(seen, widthPx, atZoom, widthPx)
                most?.let(onReading)
            }
        }

        LazyColumn(
            state = list,
            verticalArrangement = Arrangement.spacedBy(PAGE_GAP),
            contentPadding = with(density) { PaddingValues(top = topPaddingPx.toDp(), bottom = bottomPaddingPx.toDp()) },
            // Zoomed in, every drag is the gesture handler's; at fit the column scrolls itself.
            userScrollEnabled = ViewerZoom.pagerMaySwipe(zoom),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(box) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            val next = ViewerZoom.afterDoubleTap(zoom)
                            val moved = if (next == 1f) Offset.Zero else ViewerZoom.offsetForDoubleTapAt(tap, center, next)
                            offset = ViewerZoom.clampOffset(moved, next, box, box)
                            zoom = next
                            drawZoom = next
                        },
                    )
                }
                .pointerInput(box) {
                    // Before the column sees it (the Initial pass): two fingers, or any drag while zoomed,
                    // are this handler's, so the column does not scroll under a pinch.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val fingers = event.changes.count { it.pressed }
                            if (fingers > 1 || !ViewerZoom.pagerMaySwipe(zoom)) {
                                val next = ViewerZoom.afterPinch(zoom, event.calculateZoom())
                                val centroid = event.calculateCentroid(useCurrent = true)
                                    .takeIf { it != Offset.Unspecified } ?: center
                                val pan = event.calculatePan()
                                if (next <= 1f) {
                                    offset = Offset.Zero
                                } else {
                                    val pinched = ViewerZoom.offsetAfterPinch(offset, zoom, next, centroid, center, Offset.Zero)
                                    val across = ViewerZoom.clampOffset(Offset(pinched.x + pan.x, 0f), next, box, box).x
                                    val limitY = (box.height * next - box.height) / 2f
                                    val split = PdfReading.panThenScroll(pinched.y, pan.y, limitY, next)
                                    offset = Offset(across, split.offsetY)
                                    if (split.scrollBy != 0f) list.dispatchRawDelta(split.scrollBy)
                                }
                                zoom = next
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        drawZoom = zoom
                    }
                }
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            items(count = page.pageCount, key = { it }) { index ->
                val label = ViewerFormat.pdfPage(index, page.pageCount)
                val picture = drawn[index]
                val failed = index in drawn && picture == null
                PdfPageSlot(
                    heightDp = with(density) { heights.getOrElse(index) { 0 }.toDp() },
                    picture = picture,
                    failed = failed,
                    label = if (failed) "$label. ${ViewerFormat.pdfPageNotShown()}" else label,
                )
            }
        }
    }
}

/** One page's place in the column: blank at the page's shape until drawn, then the page. */
@Composable
private fun PdfPageSlot(heightDp: Dp, picture: ImageBitmap?, failed: Boolean, label: String) {
    val colors = DarkChamberTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp)
            .background(colors.surface)
            .clearAndSetSemantics { contentDescription = label },
    ) {
        when {
            picture != null ->
                Image(
                    bitmap = picture,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
            failed ->
                Text(
                    text = ViewerFormat.pdfPageNotShown(),
                    style = DarkChamberTheme.typography.body,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(DarkChamberTheme.spacing.spaceLg),
                )
        }
    }
}

/** The black between two pages. */
private val PAGE_GAP = 8.dp
