package dev.ehsanfahad.darkchamber.feature.camera.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ehsanfahad.darkchamber.core.designsystem.Aperture
import dev.ehsanfahad.darkchamber.core.designsystem.ApertureDefaults
import dev.ehsanfahad.darkchamber.core.designsystem.ApertureState
import dev.ehsanfahad.darkchamber.core.designsystem.CloseMark
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.core.designsystem.FlashMark
import dev.ehsanfahad.darkchamber.core.designsystem.SwitchCameraMark
import dev.ehsanfahad.darkchamber.core.designsystem.prefersReducedMotion
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The camera, in layout A: a bar naming where photos go and how many are there, the viewfinder, and
 * a bottom row of last-shot tile · shutter · flip.
 *
 * **It draws and decides nothing.** Whether the shutter is enabled, which controls are offered, what
 * replaces the viewfinder and every word on screen come from [CameraState] and [CameraFormat], which
 * are tested; there are no Compose UI tests, so a rule written here would be a rule nothing asserts.
 *
 * The chrome sits on the black surround rather than over the preview, so it never depends on what
 * the camera happens to be pointed at for its contrast, and the viewfinder's own gestures — tap to
 * focus, pinch to zoom — cannot be taken by a control drawn on top of it.
 */
@Composable
internal fun CameraScreen(
    state: CameraState,
    unreadableNameLabel: String,
    preview: @Composable (Modifier) -> Unit,
    lastShot: @Composable (itemId: ByteArray, modifier: Modifier) -> Unit,
    onShoot: () -> Unit,
    onFlip: () -> Unit,
    onCycleFlash: () -> Unit,
    onZoom: (Float) -> Unit,
    onFocus: (x: Float, y: Float) -> Unit,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = DarkChamberTheme.spacing
    // Bumped once per accepted press; the viewfinder and the aperture each blink on it.
    var blinks by remember { mutableIntStateOf(0) }

    val shoot = {
        blinks++
        onShoot()
    }
    val main: @Composable (Modifier) -> Unit = { area ->
        Column(modifier = area) {
            TopBar(state = state, unreadableNameLabel = unreadableNameLabel, onClose = onClose)

            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.showsViewfinder) {
                    Viewfinder(
                        zoom = state.zoom,
                        blinks = blinks,
                        preview = preview,
                        onZoom = onZoom,
                        onFocus = onFocus,
                    )
                } else {
                    Explainer(
                        explainer = state.explainer,
                        onAllow = onAllow,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }

            state.message?.let { message ->
                Text(
                    text = CameraFormat.message(message),
                    style = DarkChamberTheme.typography.body,
                    color = DarkChamberTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.spaceMd, vertical = spacing.spaceXs),
                )
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // Pure black, whatever the theme: a media surface (docs/DESIGN.md §2), and the one place
            // a raw colour is the token.
            .background(Color.Black)
            .safeDrawingPadding(),
    ) {
        when (cameraLayout(IntSize(constraints.maxWidth, constraints.maxHeight))) {
            CameraLayout.Below -> Column(modifier = Modifier.fillMaxSize()) {
                main(Modifier.weight(1f).fillMaxWidth())
                BottomRow(
                    state = state,
                    blinks = blinks,
                    lastShot = lastShot,
                    onShoot = shoot,
                    onFlip = onFlip,
                    onCycleFlash = onCycleFlash,
                )
            }
            CameraLayout.Beside -> Row(modifier = Modifier.fillMaxSize()) {
                main(Modifier.weight(1f).fillMaxHeight())
                SideColumn(
                    state = state,
                    blinks = blinks,
                    lastShot = lastShot,
                    onShoot = shoot,
                    onFlip = onFlip,
                    onCycleFlash = onCycleFlash,
                )
            }
        }
    }
}

@Composable
private fun TopBar(state: CameraState, unreadableNameLabel: String, onClose: () -> Unit) {
    val spacing = DarkChamberTheme.spacing
    val colors = DarkChamberTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.spaceXxs, vertical = spacing.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(spacing.spaceXxl)) {
            CloseMark(contentDescription = CameraFormat.closeDescription(), modifier = Modifier.size(spacing.spaceLg))
        }
        // Nothing at all while the destination is loading, gone or unreadable as a listing — a
        // placeholder title would be a name nobody gave it.
        val title = state.destination?.title?.let { CameraFormat.title(it, unreadableNameLabel) }
        Text(
            text = title.orEmpty(),
            style = DarkChamberTheme.typography.title,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = spacing.spaceXs),
        )
        CameraFormat.count(state.count)?.let { count ->
            // The sealed roles: a count of what is safely in the vault, which is what they mean.
            Text(
                text = count,
                style = DarkChamberTheme.typography.label,
                color = colors.sealed,
                maxLines = 1,
                modifier = Modifier
                    .padding(end = spacing.spaceSm)
                    .clip(DarkChamberTheme.shapes.small)
                    .background(colors.sealedContainer)
                    .padding(horizontal = spacing.spaceXs, vertical = spacing.spaceXxs),
            )
        }
    }
}

/**
 * The live preview, with tap-to-focus, pinch-to-zoom, the focus indicator and the capture blink.
 * The gestures report in the seam's terms — fractions for a tap, a ratio for a pinch — and the
 * component keeps both in range.
 */
@Composable
private fun Viewfinder(
    zoom: Float,
    blinks: Int,
    preview: @Composable (Modifier) -> Unit,
    onZoom: (Float) -> Unit,
    onFocus: (x: Float, y: Float) -> Unit,
) {
    val colors = DarkChamberTheme.colors
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var focusTaps by remember { mutableIntStateOf(0) }
    // The gesture handlers are installed once; these keep them reading the current values.
    val currentZoom by rememberUpdatedState(zoom)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnFocus by rememberUpdatedState(onFocus)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The platform's preview fills by scaling its picture past its own bounds; unclipped, it
            // draws over the bar and under the controls whenever the box is not the camera's shape.
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val (x, y) = focusFractions(tap, size) ?: return@detectTapGestures
                    focusAt = tap
                    focusTaps++
                    currentOnFocus(x, y)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, pinch, _ -> if (pinch != 1f) currentOnZoom(currentZoom * pinch) }
            },
    ) {
        preview(Modifier.fillMaxSize())

        focusAt?.let { at ->
            val half = with(density) { FocusSize.toPx() } / 2f
            // A rounded square, not a ring: the full circle is the aperture's alone (docs/DESIGN.md §4).
            Box(
                modifier = Modifier
                    .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
                    .size(FocusSize)
                    .border(2.dp, colors.accent, DarkChamberTheme.shapes.small),
            )
        }

        CaptureBlink(blinks = blinks)
    }

    LaunchedEffect(focusTaps) {
        if (focusTaps == 0) return@LaunchedEffect
        delay(FocusShownMillis)
        focusAt = null
    }
}

/**
 * The brief darkening over the viewfinder that says a photo was taken — the visual half of shutter
 * feedback, since in most regions the camera makes no sound. Under reduced motion it is a cut, not a
 * fade.
 */
@Composable
private fun CaptureBlink(blinks: Int) {
    val motion = DarkChamberTheme.motion
    val reduced = prefersReducedMotion()
    val shade = remember { Animatable(0f) }
    LaunchedEffect(blinks) {
        if (blinks == 0) return@LaunchedEffect
        shade.snapTo(BlinkDepth)
        if (reduced) {
            delay(motion.motionFast.toLong())
            shade.snapTo(0f)
        } else {
            shade.animateTo(0f, tween(durationMillis = motion.motionBase))
        }
    }
    if (shade.value > 0f) {
        Box(modifier = Modifier.fillMaxSize().alpha(shade.value).background(Color.Black))
    }
}

/** What stands where the viewfinder would, with its one useful action if it has one. */
@Composable
private fun Explainer(explainer: CameraExplainer, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    val copy = CameraFormat.explainer(explainer) ?: return
    val spacing = DarkChamberTheme.spacing
    val colors = DarkChamberTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.spaceMd),
        modifier = Modifier.widthIn(max = 360.dp).padding(horizontal = spacing.spaceLg),
    ) {
        Text(
            text = copy.text,
            style = DarkChamberTheme.typography.bodyLarge,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        copy.action?.let { action ->
            TextButton(
                onClick = if (explainer == CameraExplainer.PermanentlyDenied) onOpenSettings else onAllow,
            ) {
                Text(text = action, style = DarkChamberTheme.typography.label, color = colors.accent)
            }
        }
    }
}

/**
 * Last-shot tile at the start edge · shutter in the centre · flash at the end edge, with flip midway
 * in the gap between shutter and flash. The two halves either side of the shutter share the width
 * equally, so the shutter stays centred; a control the camera does not support is left out and its
 * place kept, so nothing else moves either.
 */
@Composable
private fun BottomRow(
    state: CameraState,
    blinks: Int,
    lastShot: @Composable (itemId: ByteArray, modifier: Modifier) -> Unit,
    onShoot: () -> Unit,
    onFlip: () -> Unit,
    onCycleFlash: () -> Unit,
) {
    val spacing = DarkChamberTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.spaceMd, vertical = spacing.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            LastShotTile(itemId = state.queue.lastSavedId, lastShot = lastShot)
        }
        Shutter(enabled = state.shutterEnabled, blinks = blinks, onShoot = onShoot)
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.weight(1f))
            FlipControl(state = state, onFlip = onFlip)
            Spacer(modifier = Modifier.weight(1f))
            FlashControl(state = state, onCycleFlash = onCycleFlash)
        }
    }
}

/**
 * The bottom row turned on its side, for a wide screen: flash at the top edge, shutter in the
 * centre, last-shot tile at the bottom edge, and flip midway in the gap between flash and shutter.
 */
@Composable
private fun SideColumn(
    state: CameraState,
    blinks: Int,
    lastShot: @Composable (itemId: ByteArray, modifier: Modifier) -> Unit,
    onShoot: () -> Unit,
    onFlip: () -> Unit,
    onCycleFlash: () -> Unit,
) {
    val spacing = DarkChamberTheme.spacing
    Column(
        modifier = Modifier.fillMaxHeight().padding(horizontal = spacing.spaceSm, vertical = spacing.spaceMd),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            FlashControl(state = state, onCycleFlash = onCycleFlash)
            Spacer(modifier = Modifier.weight(1f))
            FlipControl(state = state, onFlip = onFlip)
            Spacer(modifier = Modifier.weight(1f))
        }
        Shutter(enabled = state.shutterEnabled, blinks = blinks, onShoot = onShoot)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
            LastShotTile(itemId = state.queue.lastSavedId, lastShot = lastShot)
        }
    }
}

/** The flash control, or its empty place where the lens has no flash or nothing is showing. */
@Composable
private fun FlashControl(state: CameraState, onCycleFlash: () -> Unit) {
    val spacing = DarkChamberTheme.spacing
    Box(modifier = Modifier.size(spacing.spaceXxl)) {
        if (state.flashOffered && state.showsViewfinder) {
            IconButton(onClick = onCycleFlash, modifier = Modifier.size(spacing.spaceXxl)) {
                FlashMark(
                    glyph = flashGlyph(state.flash),
                    contentDescription = CameraFormat.flashDescription(state.flash),
                    modifier = Modifier.size(spacing.spaceLg),
                )
            }
        }
    }
}

/** The flip control, or its empty place where there is one lens or nothing is showing. */
@Composable
private fun FlipControl(state: CameraState, onFlip: () -> Unit) {
    val spacing = DarkChamberTheme.spacing
    Box(modifier = Modifier.size(spacing.spaceXxl)) {
        if (state.flipOffered && state.showsViewfinder) {
            IconButton(onClick = onFlip, modifier = Modifier.size(spacing.spaceXxl)) {
                SwitchCameraMark(
                    contentDescription = CameraFormat.flipDescription(),
                    modifier = Modifier.size(spacing.spaceLg),
                )
            }
        }
    }
}

/**
 * The newest photo this camera has saved, drawn by the host's slot through the session's loader.
 * **Display-only**: no click, no ripple, nothing that reads as a control — there is no viewer to
 * open yet, and a tile that looks tappable and does nothing is a small lie.
 */
@Composable
private fun LastShotTile(itemId: ByteArray?, lastShot: @Composable (ByteArray, Modifier) -> Unit) {
    val colors = DarkChamberTheme.colors
    val shape = DarkChamberTheme.shapes.medium
    Box(
        modifier = Modifier
            .size(TileSize)
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.outline, shape)
            .semantics { contentDescription = CameraFormat.lastShotDescription(itemId != null) },
    ) {
        if (itemId != null) lastShot(itemId, Modifier.fillMaxSize())
    }
}

/**
 * The aperture as shutter (docs/DESIGN.md §7): the one full-circle element on the screen. A press
 * blinks the iris shut and open again; a disabled shutter is drawn dimmed and does not respond.
 */
@Composable
private fun Shutter(enabled: Boolean, blinks: Int, onShoot: () -> Unit) {
    val motion = DarkChamberTheme.motion
    var shut by remember { mutableStateOf(false) }
    LaunchedEffect(blinks) {
        if (blinks == 0) return@LaunchedEffect
        shut = true
        delay(motion.motionFast.toLong())
        shut = false
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ApertureDefaults.Size)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onShoot)
            .alpha(if (enabled) 1f else DisabledAlpha),
    ) {
        Aperture(
            state = if (shut) ApertureState.Closed else ApertureState.Open,
            contentDescription = CameraFormat.shutterDescription(),
        )
    }
}

private val TileSize = 56.dp
private val FocusSize = 64.dp
private const val FocusShownMillis = 1_000L
private const val BlinkDepth = 0.7f
private const val DisabledAlpha = 0.38f
