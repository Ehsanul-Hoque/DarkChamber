package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.ehsanfahad.darkchamber.core.designsystem.BackMark
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.core.designsystem.DeleteMark
import dev.ehsanfahad.darkchamber.core.designsystem.DetailsMark
import dev.ehsanfahad.darkchamber.core.designsystem.ExcludeFromPlatformStructures
import dev.ehsanfahad.darkchamber.core.designsystem.prefersReducedMotion
import dev.ehsanfahad.darkchamber.core.designsystem.screenReaderOn
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPlayer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultViewerComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerDeleteStage
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerNotice
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerState
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Render a [VaultViewerComponent]: the feature's public composable for the viewer, beside the
 * gallery's. Everything it draws is decided in [ViewerFormat] and [ViewerZoom]; this only draws.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun VaultViewerContent(component: VaultViewerComponent, modifier: Modifier = Modifier) {
    val state by component.state.subscribeAsState()
    val pictures by component.pictures.subscribeAsState()
    val players by component.players.subscribeAsState()
    val pdfPages by component.pdfPages.subscribeAsState()
    // Ages in Details are coarse (minutes at finest); "now" once per composition is enough.
    val now = remember { Clock.System.now().toEpochMilliseconds() }
    val actions = remember(component) {
        PlayerActions(
            playPause = component::playPause,
            seekTo = component::seekTo,
            skip = component::skip,
            holdSpeed = component::holdSpeed,
            releaseSpeed = component::releaseSpeed,
            setVolume = component::setVolume,
            volume = component::volume,
            brightness = component::brightness,
            setBrightness = component::setBrightness,
            chooseSpeed = component::chooseSpeed,
            switchOrientation = component::switchOrientation,
        )
    }

    ViewerScreen(
        state = state,
        pictures = pictures,
        players = players,
        pdfPages = pdfPages,
        playerActions = actions,
        now = now,
        onPdfViewport = component::pdfViewport,
        onPdfScrolled = component::pdfScrolled,
        onViewport = component::viewportMeasured,
        onShowPage = component::showPage,
        onBack = component::back,
        onRequestDelete = component::requestDelete,
        onConfirmDelete = component::confirmDelete,
        onCancelDelete = component::cancelDelete,
        onDismissNotice = component::dismissNotice,
        modifier = modifier,
    )
}

/**
 * The viewer: a pager on pure black, the picture running under the system bars, with bars over it
 * that a tap hides and shows.
 */
@Composable
internal fun ViewerScreen(
    state: ViewerState,
    pictures: Map<String, ImageBitmap>,
    players: Map<String, VaultPlayer>,
    pdfPages: Map<Int, ImageBitmap?>,
    playerActions: PlayerActions,
    now: Long,
    onPdfViewport: (visible: IntRange, shownWidthPx: Int, zoom: Float, screenWidthPx: Int) -> Unit,
    onPdfScrolled: (page: Int, offsetPx: Int) -> Unit,
    onViewport: (widthPx: Int, heightPx: Int) -> Unit,
    onShowPage: (Int) -> Unit,
    onBack: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var barsShown by rememberSaveable { mutableStateOf(true) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    // Whether the page shown is zoomed in. While it is, a drag pans the picture rather than turning
    // the page.
    var zoomedIn by remember { mutableStateOf(false) }
    // The bars' heights as last measured on screen, so a PDF's first page can start below the top bar
    // and its last end above the bottom one. The bottom bar is measured only where it is one row of
    // actions, as on a PDF page — a player page's is taller.
    var topBarPx by remember { mutableIntStateOf(0) }
    var bottomBarPx by remember { mutableIntStateOf(0) }

    // Pure black whatever the theme: a media surface (docs/DESIGN.md §2), where a raw colour is the token.
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(Color.Black)) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        LaunchedEffect(widthPx, heightPx) { onViewport(widthPx, heightPx) }

        val shown: GalleryItem? = state.items.getOrNull(state.index)
        if (!state.loading && shown != null) {
            val shownKey = shown.id.toListKey()
            val media = state.pages[shownKey] as? ViewerPage.Media
            val pdf = state.pages[shownKey] as? ViewerPage.Pdf
            // The page of the PDF shown that the indicator names: where it opens, then what is most on screen.
            var pdfReading by remember(shownKey, pdf?.start) { mutableIntStateOf(pdf?.start?.page ?: 0) }
            // How long the page shown has been loading, as far as the indicator cares: reset in the same
            // frame a new page is shown (so it never inherits the last one's wait), and moved on once.
            val pageLoading = state.pages[shownKey] == ViewerPage.Loading
            var loadingMs by remember(shownKey, pageLoading) { mutableLongStateOf(0L) }
            LaunchedEffect(shownKey, pageLoading) {
                if (pageLoading) {
                    delay(ViewerFormat.PDF_DASHES_AFTER_MS)
                    loadingMs = ViewerFormat.PDF_DASHES_AFTER_MS
                }
            }
            // Jump to a page: the dialog, and the jump it asked for — both for this item only.
            var jumpOpen by rememberSaveable(shownKey) { mutableStateOf(false) }
            var jumpRequest by remember(shownKey) { mutableStateOf<PdfJumpRequest?>(null) }
            val shownPlayer = players[shownKey]
            val playerState: PlayerState? = key(shownPlayer) { shownPlayer?.state?.collectAsState()?.value }
            // An audio page's controls stay on screen; a video page's hide after a while of playing,
            // counted again from each touch of a control.
            val barsVisible = PlayerChrome.barsVisible(media?.kind, barsShown)
            var interactions by remember { mutableIntStateOf(0) }
            val autoHides = PlayerChrome.autoHides(media?.kind, playerState, screenReader = screenReaderOn())
            LaunchedEffect(barsShown, autoHides, interactions) {
                if (barsShown && autoHides) {
                    delay(AUTO_HIDE_MS)
                    barsShown = false
                }
            }

            val pager = rememberPagerState(initialPage = state.index) { state.items.size }
            // The pager settling is the user's choice of page; the component's index is the listing's
            // answer when it changes under the pager (a delete, an import). Each follows the other.
            LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect(onShowPage) }
            LaunchedEffect(state.index) {
                if (pager.currentPage != state.index && !pager.isScrollInProgress) pager.scrollToPage(state.index)
            }

            HorizontalPager(
                state = pager,
                key = { page -> state.items[page].id.toListKey() },
                // At most one page either side is ever composed, and only while a swipe is moving.
                beyondViewportPageCount = 0,
                userScrollEnabled = !zoomedIn,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = state.items[page]
                val key = item.id.toListKey()
                val isShown = page == pager.currentPage
                ViewerPageView(
                    page = state.pages[key] ?: ViewerPage.Loading,
                    picture = pictures[key],
                    item = item,
                    isShown = isShown,
                    onTap = { barsShown = !barsShown },
                    onZoomedIn = { if (isShown) zoomedIn = it },
                    player = players[key],
                    playerActions = playerActions,
                    onInteraction = { interactions++ },
                    controlsShown = barsVisible,
                    pdf = PdfHooks(
                        drawn = if (isShown) pdfPages else emptyMap(),
                        onViewport = onPdfViewport,
                        onScrolled = onPdfScrolled,
                        onReading = { if (isShown) pdfReading = it },
                        topPaddingPx = topBarPx,
                        bottomPaddingPx = bottomBarPx,
                        jump = if (isShown) jumpRequest else null,
                        barsShown = barsVisible,
                    ),
                )
            }

            if (media != null && playerState != null) {
                CentreControlButton(
                    visible = barsVisible,
                    state = playerState,
                    onClick = {
                        playerActions.playPause()
                        interactions++
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            ViewerTopBar(
                visible = barsVisible,
                title = ViewerFormat.title(shown.meta),
                position = ViewerFormat.position(state.index, state.items.size),
                onBack = onBack,
                modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { if (it.height > 0) topBarPx = it.height },
            )
            ViewerBottomBar(
                visible = barsVisible,
                onDetails = { detailsOpen = true },
                onDelete = onRequestDelete,
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged {
                    if (it.height > 0 && media == null) bottomBarPx = it.height
                },
                leading = ViewerFormat.pdfIndicator(
                    page = state.pages[shownKey] ?: ViewerPage.Loading,
                    meta = shown.meta,
                    reading = pdfReading,
                    loadingMs = loadingMs,
                )?.let { slot ->
                    when (slot) {
                        // Nothing drawn and nothing to land on: the place alone keeps Details and Delete still.
                        IndicatorSlot.Held -> { {} }
                        is IndicatorSlot.Shown -> {
                            { PdfIndicator(slot.indicator, onClick = if (slot.indicator.ready) ({ jumpOpen = true }) else null) }
                        }
                    }
                },
                controls = if (media != null && playerState != null) {
                    { trailing ->
                        PlayerControls(
                            kind = media.kind,
                            state = playerState,
                            speed = state.speed,
                            orientationTarget = state.orientationTarget,
                            actions = playerActions,
                            onInteraction = { interactions++ },
                            trailing = trailing,
                        )
                    }
                } else {
                    null
                },
                // A phone on its side: one row under the seek bar, clear of the centre control.
                compact = PlayerChrome.compactBar(maxHeight.value),
            )
            if (jumpOpen && pdf != null) {
                PdfJumpDialog(
                    pageCount = pdf.pageCount,
                    onGo = { index ->
                        jumpRequest = PdfJumpRequest(index)
                        jumpOpen = false
                    },
                    onCancel = { jumpOpen = false },
                )
            }
            if (detailsOpen) {
                DetailsSheet(rows = ViewerFormat.details(shown, shown.album, now), onDismiss = { detailsOpen = false })
            }
        }

        state.notice?.let { notice ->
            NoticeBanner(
                text = ViewerFormat.notice(notice),
                warning = notice == ViewerNotice.DeleteFailed,
                onDismiss = onDismissNotice,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }

    state.delete?.let { stage ->
        DeleteDialog(deleting = stage == ViewerDeleteStage.Deleting, onConfirm = onConfirmDelete, onCancel = onCancelDelete)
    }
}

/** One page: the picture, the wait, or the words for why there is no picture. */
@Composable
private fun ViewerPageView(
    page: ViewerPage,
    picture: ImageBitmap?,
    item: GalleryItem,
    isShown: Boolean,
    onTap: () -> Unit,
    onZoomedIn: (Boolean) -> Unit,
    player: VaultPlayer?,
    playerActions: PlayerActions,
    onInteraction: () -> Unit,
    controlsShown: Boolean,
    pdf: PdfHooks,
) {
    when {
        page is ViewerPage.Pdf ->
            PdfPageView(
                page = page,
                drawn = pdf.drawn,
                isShown = isShown,
                onTap = onTap,
                onZoomedIn = onZoomedIn,
                onViewport = pdf.onViewport,
                onScrolled = pdf.onScrolled,
                onReading = pdf.onReading,
                topPaddingPx = pdf.topPaddingPx,
                bottomPaddingPx = pdf.bottomPaddingPx,
                jump = pdf.jump,
                barsShown = pdf.barsShown,
            )
        page is ViewerPage.Media ->
            PlayerPageView(
                kind = page.kind,
                badge = ViewerFormat.pageBadge(page, item.meta),
                note = page.note,
                player = player,
                actions = playerActions,
                onToggleBars = onTap,
                onInteraction = onInteraction,
                controlsShown = controlsShown,
            )
        page == ViewerPage.Picture && picture != null ->
            ZoomablePicture(
                picture = picture,
                description = ViewerFormat.title(item.meta),
                isShown = isShown,
                onTap = onTap,
                onZoomedIn = onZoomedIn,
            )
        page == ViewerPage.Loading -> QuietWait(onTap)
        else -> PageMessage(page = page, badge = ViewerFormat.pageBadge(page, item.meta), onTap = onTap)
    }
}

/**
 * The picture, fitted, with pinch and double-tap zoom to [ViewerZoom.MAX] and pan when zoomed. A
 * single-finger drag at fit is left alone so the pager can turn the page; zoomed in, or with two
 * fingers down, this takes the gesture. Zoom returns to fit when the page stops being the one shown.
 */
@Composable
private fun ZoomablePicture(
    picture: ImageBitmap,
    description: String,
    isShown: Boolean,
    onTap: () -> Unit,
    onZoomedIn: (Boolean) -> Unit,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(isShown) {
        if (!isShown) {
            zoom = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(zoom) { onZoomedIn(!ViewerZoom.pagerMaySwipe(zoom)) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val box = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val content = ViewerZoom.fitted(Size(picture.width.toFloat(), picture.height.toFloat()), box)
        val center = Offset(box.width / 2f, box.height / 2f)

        Image(
            bitmap = picture,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.High,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(box, content) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            val next = ViewerZoom.afterDoubleTap(zoom)
                            val moved = if (next == 1f) Offset.Zero else ViewerZoom.offsetForDoubleTapAt(tap, center, next)
                            offset = ViewerZoom.clampOffset(moved, next, content, box)
                            zoom = next
                        },
                    )
                }
                .pointerInput(box, content) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            if (fingers > 1 || !ViewerZoom.pagerMaySwipe(zoom)) {
                                val next = ViewerZoom.afterPinch(zoom, event.calculateZoom())
                                val centroid = event.calculateCentroid(useCurrent = true)
                                    .takeIf { it != Offset.Unspecified } ?: center
                                val moved = ViewerZoom.offsetAfterPinch(offset, zoom, next, centroid, center, event.calculatePan())
                                offset = if (next <= 1f) Offset.Zero else ViewerZoom.clampOffset(moved, next, content, box)
                                zoom = next
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/** Nothing for a moment, then a small indicator — a quick decode never flashes one. */
@Composable
private fun QuietWait(onTap: () -> Unit) {
    var showIndicator by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(INDICATOR_DELAY_MS)
        showIndicator = true
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
    ) {
        if (showIndicator) {
            CircularProgressIndicator(color = DarkChamberTheme.colors.textSecondary, modifier = Modifier.size(32.dp))
        }
    }
}

/** A page with no picture: the file's badge and what the page has to say. */
@Composable
private fun PageMessage(page: ViewerPage, badge: String, onTap: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    val warning = ViewerFormat.pageIsWarning(page)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = spacing.spaceXl),
    ) {
        Text(
            text = badge,
            style = DarkChamberTheme.typography.title,
            color = if (warning) colors.danger else colors.textSecondary,
        )
        ViewerFormat.pageMessage(page)?.let { message ->
            Text(
                text = message,
                style = DarkChamberTheme.typography.body,
                color = if (warning) colors.danger else colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.spaceSm).widthIn(max = 360.dp),
            )
        }
    }
}

@Composable
private fun barsTransition(): Int = if (prefersReducedMotion()) 0 else DarkChamberTheme.motion.motionBase

/** Back, the item's name, and where it stands in the listing — on the bars' flat scrim. */
@Composable
private fun ViewerTopBar(visible: Boolean, title: String, position: String, onBack: () -> Unit, modifier: Modifier) {
    val duration = barsTransition()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(duration)),
        exit = fadeOut(tween(duration)),
        modifier = modifier.fillMaxWidth(),
    ) {
        val colors = DarkChamberTheme.colors
        val spacing = DarkChamberTheme.spacing
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(ViewerBars.scrim)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = spacing.spaceXs, vertical = spacing.spaceXs),
        ) {
            IconButton(onClick = onBack) {
                BackMark(contentDescription = ViewerFormat.back())
            }
            Text(
                text = title,
                style = DarkChamberTheme.typography.title,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = spacing.spaceXs),
            )
            Text(
                text = position,
                style = DarkChamberTheme.typography.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(end = spacing.spaceSm),
            )
        }
    }
}

/**
 * Details and Delete, labelled, Delete in the danger role — on the bars' flat scrim.
 * A player page puts its [controls] above them.
 */
@Composable
private fun ViewerBottomBar(
    visible: Boolean,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
    /** A player page's controls, given whatever should end their row (see [compact]). */
    controls: (@Composable (trailing: (@Composable () -> Unit)?) -> Unit)? = null,
    /** A short screen: Details and Delete end the controls' row rather than taking a row of their own. */
    compact: Boolean = false,
    /** What starts the row of Details and Delete: a PDF's page indicator. */
    leading: (@Composable () -> Unit)? = null,
) {
    val duration = barsTransition()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(duration)),
        exit = fadeOut(tween(duration)),
        modifier = modifier.fillMaxWidth(),
    ) {
        val colors = DarkChamberTheme.colors
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ViewerBars.scrim)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(vertical = DarkChamberTheme.spacing.spaceXs),
        ) {
            val details: @Composable () -> Unit = {
                BarAction(label = ViewerFormat.detailsAction(), color = colors.textPrimary, onClick = onDetails) { tint ->
                    DetailsMark(contentDescription = null, tint = tint)
                }
            }
            val delete: @Composable () -> Unit = {
                BarAction(label = ViewerFormat.deleteAction(1), color = colors.danger, onClick = onDelete) { tint ->
                    DeleteMark(contentDescription = null, tint = tint)
                }
            }
            val barActions: @Composable () -> Unit = {
                details()
                delete()
            }
            if (controls != null && compact) {
                controls(barActions)
            } else {
                controls?.invoke(null)
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (leading == null) {
                        barActions()
                    } else {
                        // Three equal slots: whatever the indicator says ("Page – of –", "Page 9",
                        // "Page 100"), Details and Delete stay where they are.
                        for (slot in listOf(leading, details, delete)) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f)) { slot() }
                        }
                    }
                }
            }
        }
    }
}

/** What a PDF page needs from the screen: its drawn pages, and where to say what it shows. */
private class PdfHooks(
    val drawn: Map<Int, ImageBitmap?>,
    val onViewport: (visible: IntRange, shownWidthPx: Int, zoom: Float, screenWidthPx: Int) -> Unit,
    val onScrolled: (page: Int, offsetPx: Int) -> Unit,
    val onReading: (Int) -> Unit,
    val topPaddingPx: Int,
    val bottomPaddingPx: Int,
    /** A jump to a page asked for in the dialog, while this page is the one shown. */
    val jump: PdfJumpRequest?,
    /** Whether the bars are on screen: the fast-scroll thumb shows with them. */
    val barsShown: Boolean,
)

/**
 * "Page N of M", as tall as the actions beside it, spoken as [PageIndicator.spoken]. A button that
 * opens the jump dialog once the pages are known ([onClick]); plain text before.
 */
@Composable
private fun PdfIndicator(indicator: PageIndicator, onClick: (() -> Unit)?) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 72.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = indicator.spoken }
            .padding(DarkChamberTheme.spacing.spaceXs),
    ) {
        Text(
            text = indicator.text,
            style = DarkChamberTheme.typography.label,
            color = DarkChamberTheme.colors.textPrimary,
            // Two lines at a large font ("Page N" over "of M"), each centred under the other.
            textAlign = TextAlign.Center,
            // Spoken once, as the box's description, not again as its text.
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** An icon over its label, one 48 dp target, spoken as its label. */
@Composable
private fun BarAction(label: String, color: Color, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(DarkChamberTheme.spacing.spaceXs),
    ) {
        icon(color)
        Text(text = label, style = DarkChamberTheme.typography.label, color = color)
    }
}

/** The item's details: its own window, so its own exclusion from the platform's structures. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(rows: List<ViewerFormat.DetailRow>, onDismiss: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        ExcludeFromPlatformStructures()
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = spacing.spaceLg, vertical = spacing.spaceSm)) {
            Text(
                text = ViewerFormat.detailsAction(),
                style = DarkChamberTheme.typography.title,
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = spacing.spaceSm),
            )
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.spaceXs)) {
                    Text(
                        text = row.label,
                        style = DarkChamberTheme.typography.body,
                        color = colors.textSecondary,
                        modifier = Modifier.widthIn(min = 88.dp),
                    )
                    Text(
                        text = row.value,
                        style = DarkChamberTheme.typography.body,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The confirmation: what will happen, a neutral cancel and the destructive action in the danger role.
 * Once confirmed nothing on it responds — a shred cannot be called back — and the action says why.
 */
@Composable
private fun DeleteDialog(deleting: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnBackPress = !deleting, dismissOnClickOutside = !deleting),
    ) {
        // Its own window, so its own exclusion.
        ExcludeFromPlatformStructures()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = colors.surface, shape = DarkChamberTheme.shapes.large)
                .padding(spacing.spaceLg),
        ) {
            Text(text = ViewerFormat.deleteQuestion(1), style = DarkChamberTheme.typography.title, color = colors.textPrimary)
            Text(
                text = ViewerFormat.deleteExplanation(1),
                style = DarkChamberTheme.typography.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.spaceSm),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceMd)) {
                TextButton(onClick = onCancel, enabled = !deleting) {
                    Text(
                        text = ViewerFormat.cancel(),
                        style = DarkChamberTheme.typography.label,
                        color = if (deleting) colors.textSecondary else colors.textPrimary,
                    )
                }
                TextButton(onClick = onConfirm, enabled = !deleting) {
                    Text(
                        text = if (deleting) ViewerFormat.deleting() else ViewerFormat.deleteAction(1),
                        style = DarkChamberTheme.typography.label,
                        color = if (deleting) colors.textSecondary else colors.danger,
                    )
                }
            }
        }
    }
}

/** What a finished delete reports, gone after a few seconds or on a tap. */
@Composable
private fun NoticeBanner(text: String, warning: Boolean, onDismiss: () -> Unit, modifier: Modifier) {
    val colors = DarkChamberTheme.colors
    LaunchedEffect(text) {
        delay(NOTICE_MS)
        onDismiss()
    }
    Text(
        text = text,
        style = DarkChamberTheme.typography.body,
        color = if (warning) colors.danger else colors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(DarkChamberTheme.spacing.spaceLg)
            .background(color = colors.surfaceRaised, shape = DarkChamberTheme.shapes.medium)
            .clickable(onClick = onDismiss)
            .padding(horizontal = DarkChamberTheme.spacing.spaceMd, vertical = DarkChamberTheme.spacing.spaceSm),
    )
}

/**
 * The player's controls over a picture (the bubbles and the centre control): black, like the camera's
 * bands — a media surface, so a raw colour. The bars use [ViewerBars.scrim], darker.
 */
internal val ScrimDark = Color.Black.copy(alpha = 0.6f)

private const val INDICATOR_DELAY_MS = 400L
private const val NOTICE_MS = 4_000L

/** How long a playing video's controls stay after the last touch. */
private const val AUTO_HIDE_MS = 3_000L
