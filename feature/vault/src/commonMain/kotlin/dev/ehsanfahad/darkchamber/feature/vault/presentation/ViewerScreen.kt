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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
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
        playerActions = actions,
        now = now,
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
    playerActions: PlayerActions,
    now: Long,
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

    // Pure black whatever the theme: a media surface (docs/DESIGN.md §2), where a raw colour is the token.
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(Color.Black)) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        LaunchedEffect(widthPx, heightPx) { onViewport(widthPx, heightPx) }

        val shown: GalleryItem? = state.items.getOrNull(state.index)
        if (!state.loading && shown != null) {
            val shownKey = shown.id.toListKey()
            val media = state.pages[shownKey] as? ViewerPage.Media
            val shownPlayer = players[shownKey]
            val playerState: PlayerState? = key(shownPlayer) { shownPlayer?.state?.collectAsState()?.value }
            // An audio page's controls stay on screen; a video page's hide after a while of playing,
            // counted again from each touch of a control.
            val barsVisible = PlayerChrome.barsVisible(media?.kind, barsShown)
            var interactions by remember { mutableIntStateOf(0) }
            val autoHides = PlayerChrome.autoHides(media?.kind, playerState)
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
                modifier = Modifier.align(Alignment.TopCenter),
            )
            ViewerBottomBar(
                visible = barsVisible,
                onDetails = { detailsOpen = true },
                onDelete = onRequestDelete,
                modifier = Modifier.align(Alignment.BottomCenter),
                controls = if (media != null && playerState != null) {
                    {
                        PlayerControls(
                            kind = media.kind,
                            state = playerState,
                            speed = state.speed,
                            orientationTarget = state.orientationTarget,
                            actions = playerActions,
                            onInteraction = { interactions++ },
                        )
                    }
                } else {
                    null
                },
            )
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
) {
    when {
        page is ViewerPage.Media ->
            PlayerPageView(
                kind = page.kind,
                badge = ViewerFormat.pageBadge(page, item.meta),
                note = page.note,
                player = player,
                actions = playerActions,
                onToggleBars = onTap,
                onInteraction = onInteraction,
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

/** Back, the item's name, and where it stands in the listing — over a scrim fading down into the picture. */
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
                .background(Brush.verticalGradient(listOf(ScrimDark, Color.Transparent)))
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
 * Details and Delete, labelled, Delete in the danger role — over a scrim rising from the bottom edge.
 * A player page puts its [controls] above them.
 */
@Composable
private fun ViewerBottomBar(
    visible: Boolean,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
    controls: (@Composable () -> Unit)? = null,
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
                .background(Brush.verticalGradient(listOf(Color.Transparent, ScrimDark)))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(vertical = DarkChamberTheme.spacing.spaceXs),
        ) {
            controls?.invoke()
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                BarAction(label = ViewerFormat.detailsAction(), color = colors.textPrimary, onClick = onDetails) { tint ->
                    DetailsMark(contentDescription = null, tint = tint)
                }
                BarAction(label = ViewerFormat.deleteAction(1), color = colors.danger, onClick = onDelete) { tint ->
                    DeleteMark(contentDescription = null, tint = tint)
                }
            }
        }
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
 * The bars' scrim, and the player's controls over a picture: black, like the camera's bands — a media
 * surface, so a raw colour.
 */
internal val ScrimDark = Color.Black.copy(alpha = 0.6f)

private const val INDICATOR_DELAY_MS = 400L
private const val NOTICE_MS = 4_000L

/** How long a playing video's controls stay after the last touch. */
private const val AUTO_HIDE_MS = 3_000L
