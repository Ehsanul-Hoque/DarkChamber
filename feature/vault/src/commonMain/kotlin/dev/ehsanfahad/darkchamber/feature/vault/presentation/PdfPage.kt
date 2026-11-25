package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A PDF's pages, stacked and fitted to the width, scrolling up and down — left and right still turn
 * the viewer's page. Each page has its slot at its own shape before it is drawn, so the column never
 * jumps as pictures arrive. Pinch and double-tap zoom the whole column ([ViewerZoom]'s rules); zoomed
 * in, a drag moves across the page and, past its edge, on through the pages ([PdfReading.zoomedStep]),
 * and a drag let go at speed flings on by the same rule until it slows, reaches an end or is touched.
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
    /** A jump to a page from the dialog: the page lands at the top, at fit. */
    jump: PdfJumpRequest? = null,
    /** Whether the viewer's bars are on screen: the fast-scroll thumb shows with them. */
    barsShown: Boolean = false,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // The zoom pages are drawn for: taken when a gesture ends, so a pinch does not redraw every frame.
    var drawZoom by remember { mutableFloatStateOf(1f) }
    val list = rememberLazyListState(page.start.page, page.start.offsetPx)
    val shown by rememberUpdatedState(isShown)
    // A fling of the zoomed column, while it runs; the decay is the one a scroll uses.
    val flingScope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Offset>()
    var fling by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(isShown) {
        if (!isShown) {
            fling?.cancel()
            zoom = 1f
            offset = Offset.Zero
            drawZoom = 1f
        }
    }
    LaunchedEffect(zoom) { onZoomedIn(!ViewerZoom.pagerMaySwipe(zoom)) }
    LaunchedEffect(jump) {
        if (jump == null) return@LaunchedEffect
        // Back to fit first: a page landing under a zoom would show some corner of it.
        fling?.cancel()
        zoom = 1f
        offset = Offset.Zero
        drawZoom = 1f
        list.scrollToItem(jump.index)
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { (index, offsetPx) -> if (shown) onScrolled(index, offsetPx) }
    }
    // The fast-scroll thumb: shown for a while after the column last moved, and while it is dragged.
    var scrolledLately by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // The page most on screen, for the bubble beside the thumb.
    var reading by remember { mutableIntStateOf(page.start.page) }
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
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        // The column fills the box, so it is what is zoomed and what overhangs.
        val box = Size(widthPx.toFloat(), heightPx.toFloat())
        val center = Offset(box.width / 2f, box.height / 2f)
        val density = LocalDensity.current
        // Each slot's height as laid out: through dp and back, once, so the thumb's arithmetic and the
        // column agree to the pixel — over hundreds of pages a pixel each would put the thumb off.
        val heights = remember(page.pageSizes, widthPx, density) {
            page.pageSizes.map { with(density) { PdfReading.pageHeight(widthPx, it.widthPt, it.heightPt).toDp().roundToPx() } }
        }
        val gapPx = with(density) { PAGE_GAP.roundToPx() }
        val tops = remember(heights, gapPx) { FastScroll.tops(heights, gapPx) }
        val contentHeight = remember(heights, gapPx) { FastScroll.contentHeight(heights, gapPx) }
        val maxScroll = FastScroll.maxScroll(contentHeight, topPaddingPx, bottomPaddingPx, heightPx)
        val thumbHeightPx = with(density) { THUMB_HEIGHT.roundToPx() }
        val stripPx = with(density) { THUMB_STRIP.roundToPx() }
        val slopPx = with(density) { THUMB_SLOP.roundToPx() }
        val trackTop = topPaddingPx
        val trackBottom = heightPx - bottomPaddingPx
        val fraction = if (dragging) {
            dragFraction
        } else {
            FastScroll.fraction(tops, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, maxScroll)
        }
        val thumbTop = FastScroll.thumbTop(fraction, trackTop, trackBottom, thumbHeightPx)
        val thumbShown = FastScroll.visible(
            worthIt = FastScroll.worthIt(contentHeight, heightPx),
            zoomedIn = !ViewerZoom.pagerMaySwipe(zoom),
            barsShown = barsShown,
            scrolledLately = scrolledLately,
            dragging = dragging,
        )
        // Read by the gesture handler, which outlives a composition.
        val grab by rememberUpdatedState(Triple(thumbShown, thumbTop, fraction))
        val track by rememberUpdatedState(Triple(trackTop, trackBottom, maxScroll))
        val pageTops by rememberUpdatedState(tops)

        LaunchedEffect(list, widthPx, heightPx, isShown) {
            if (!isShown) return@LaunchedEffect
            snapshotFlow {
                val band = PdfReading.seenBand(heightPx, zoom, offset.y)
                // The list counts from the end of its top padding; the band counts from the box's top.
                val before = -list.layoutInfo.viewportStartOffset
                val laidOut = list.layoutInfo.visibleItemsInfo.map { LaidOutPage(it.index, it.offset + before, it.size) }
                Seen(
                    pages = PdfReading.pagesSeen(laidOut, band.top, band.bottom),
                    most = PdfReading.mostOnScreen(laidOut, band.top, band.bottom),
                    zoom = drawZoom,
                    dragging = dragging,
                )
            }.distinctUntilChanged().collect { seen ->
                // A fast-scroll drag sweeps past pages it would be a waste to draw: only where it ends.
                if (!seen.dragging) onViewport(seen.pages, widthPx, seen.zoom, widthPx)
                seen.most?.let {
                    reading = it
                    onReading(it)
                }
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
                .pointerInput(box, stripPx, thumbHeightPx, slopPx) {
                    // First, before anything else sees it: a press on the fast-scroll thumb is the
                    // thumb's whole gesture. Any other press is left alone for the column and the pager.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val (shownNow, topNow, fractionNow) = grab
                        val grabbed = shownNow &&
                            FastScroll.grabs(down.position.x, down.position.y, widthPx, stripPx, topNow, thumbHeightPx, slopPx)
                        if (!grabbed) return@awaitEachGesture
                        down.consume()
                        // Where on the thumb the finger is, so the thumb does not jump to put its top there.
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
                                val place = FastScroll.placeAt(next, pageTops, scrollable)
                                list.requestScrollToItem(place.page, place.offsetPx)
                                event.changes.forEach { it.consume() }
                            }
                        } finally {
                            dragging = false
                        }
                    }
                }
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
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // A touch stops a fling: the page stays under the finger.
                        fling?.cancel()
                        val tracker = VelocityTracker()
                        tracker.addPointerInputChange(down)
                        var pinched = false
                        var draggedZoomed = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val fingers = event.changes.count { it.pressed }
                            if (fingers > 1) pinched = true
                            if (fingers > 1 || !ViewerZoom.pagerMaySwipe(zoom)) {
                                val next = ViewerZoom.afterPinch(zoom, event.calculateZoom())
                                val centroid = event.calculateCentroid(useCurrent = true)
                                    .takeIf { it != Offset.Unspecified } ?: center
                                val pan = event.calculatePan()
                                if (next <= 1f) {
                                    offset = Offset.Zero
                                } else {
                                    val pinchedTo = ViewerZoom.offsetAfterPinch(offset, zoom, next, centroid, center, Offset.Zero)
                                    val step = PdfReading.zoomedStep(pinchedTo, pan, next, box)
                                    offset = step.offset
                                    if (step.scrollBy != 0f) list.dispatchRawDelta(step.scrollBy)
                                    draggedZoomed = true
                                }
                                zoom = next
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            event.changes.firstOrNull { it.id == down.id }?.let { tracker.addPointerInputChange(it) }
                        } while (event.changes.any { it.pressed })
                        drawZoom = zoom
                        val thrown = if (draggedZoomed && !ViewerZoom.pagerMaySwipe(zoom)) {
                            val velocity = tracker.calculateVelocity()
                            PdfReading.flingVelocity(
                                Offset(velocity.x, velocity.y),
                                minPxPerSecond = MIN_FLING_VELOCITY.toPx(),
                                maxPxPerSecond = viewConfiguration.maximumFlingVelocity,
                                pinched = pinched,
                            )
                        } else {
                            null
                        }
                        if (thrown != null) {
                            fling = flingScope.launch {
                                // The column moves on as the finger left it, slowing as a scroll does, by
                                // the same rule as the drag; it stops at its ends, at fit, or at a touch.
                                var last = Offset.Zero
                                AnimationState(Offset.VectorConverter, Offset.Zero, thrown).animateDecay(decay) {
                                    val delta = value - last
                                    last = value
                                    val before = offset
                                    val step = PdfReading.zoomedStep(before, delta, zoom, box)
                                    offset = step.offset
                                    val scrolled = if (step.scrollBy != 0f) list.dispatchRawDelta(step.scrollBy) else 0f
                                    if (!PdfReading.flingGoesOn(zoom, delta, before, step.offset, scrolled)) cancelAnimation()
                                }
                            }
                        }
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

        FastScrollThumb(
            shown = thumbShown,
            topPx = thumbTop,
            dragging = dragging,
            bubble = ViewerFormat.fastScrollPage(reading),
        )
    }
}

/** What the column shows, as the viewer is told it. */
private data class Seen(val pages: IntRange, val most: Int?, val zoom: Float, val dragging: Boolean)

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

/** The slowest a zoomed drag can be let go at and still fling: Android's own minimum fling speed. */
private val MIN_FLING_VELOCITY = 50.dp
