package dev.ehsanfahad.darkchamber.feature.camera.presentation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ehsanfahad.darkchamber.core.designsystem.Aperture
import dev.ehsanfahad.darkchamber.core.designsystem.ApertureDefaults
import dev.ehsanfahad.darkchamber.core.designsystem.ApertureState
import dev.ehsanfahad.darkchamber.core.designsystem.CloseMark
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.core.designsystem.FlashMark
import dev.ehsanfahad.darkchamber.core.designsystem.MicrophoneMark
import dev.ehsanfahad.darkchamber.core.designsystem.SwitchCameraMark
import dev.ehsanfahad.darkchamber.core.designsystem.TorchMark
import dev.ehsanfahad.darkchamber.core.designsystem.prefersReducedMotion
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.domain.ActiveRecording
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The camera: a bar naming where captures go and how many are there, the viewfinder at the shape of
 * what it captures, the mode labels (or, while recording, the status), and a bottom row of last-shot
 * tile · shutter · flip · light.
 *
 * **It draws and decides nothing.** Whether the shutter records or stops, which controls are offered,
 * which notice shows, what replaces the viewfinder, how far the icons turn and every word on screen
 * come from [CameraState], [iconTurn] and [CameraFormat], which are tested; there are no Compose UI
 * tests, so a rule written here would be a rule nothing asserts.
 *
 * **The viewfinder shows exactly the frame that is saved**: a 4:3 box for photos, under the bar, and a
 * 16:9 box for video, which nearly fills a tall phone — so the bar and the controls sit over its edges
 * on dark fades, keeping their contrast whatever the camera is pointed at. The viewfinder's own
 * gestures, tap to focus and pinch to zoom, are taken only where no control is drawn.
 *
 * **Icons turn with the device; text does not.** The screen holds portrait, so the ✕, flip, light,
 * chip, microphone and tile marks are turned to stay upright for whoever holds the phone, and the
 * title, count, mode labels and status stay as drawn.
 *
 * **Edge to edge.** The host does not keep this screen clear of the system bars: the black and the
 * viewfinder run under them, and the bar and the bottom controls place themselves inside the insets,
 * their fades running on under the bars. The wide fallback is simply inset whole.
 */
@Composable
internal fun CameraScreen(
    state: CameraState,
    tilt: Int,
    unreadableNameLabel: String,
    preview: @Composable (Modifier) -> Unit,
    lastShot: @Composable (itemId: ByteArray, modifier: Modifier) -> Unit,
    onShoot: () -> Unit,
    onSetMode: (CaptureMode) -> Unit,
    onFlip: () -> Unit,
    onCycleFlash: () -> Unit,
    onToggleTorch: () -> Unit,
    onSwitchQuality: () -> Unit,
    onToggleSound: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onZoom: (Float) -> Unit,
    onFocus: (x: Float, y: Float) -> Unit,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bumped once per photo taken; the viewfinder and the aperture each blink on it. A recording's
    // start and stop are shown by the shutter itself changing, so they do not blink.
    var blinks by remember { mutableIntStateOf(0) }
    val shoot = {
        if (state.shutter.kind == ShutterKind.Photo && state.shutter.enabled) blinks++
        onShoot()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // Pure black, whatever the theme: a media surface (docs/DESIGN.md §2), and the one place
            // a raw colour is the token.
            .background(Color.Black),
    ) {
        val layout = cameraLayout(IntSize(constraints.maxWidth, constraints.maxHeight))
        val angle = rememberIconAngle(tilt, layout)
        val controls = Controls(
            state = state,
            angle = angle,
            blinks = blinks,
            lastShot = lastShot,
            onShoot = shoot,
            onFlip = onFlip,
            onCycleFlash = onCycleFlash,
            onToggleTorch = onToggleTorch,
        )
        when (layout) {
            CameraLayout.Below -> Box(modifier = Modifier.fillMaxSize()) {
                var barHeight by remember { mutableIntStateOf(0) }
                val barDp = with(LocalDensity.current) { barHeight.toDp() }
                ViewfinderArea(
                    state = state,
                    layout = layout,
                    blinks = blinks,
                    preview = preview,
                    onZoom = onZoom,
                    onFocus = onFocus,
                    onAllow = onAllow,
                    onOpenSettings = onOpenSettings,
                    // A photo's 4:3 box sits under the bar; a clip's 16:9 box is centred, under the
                    // chrome's fades.
                    topInset = if (state.mode == CaptureMode.Photo) barDp else 0.dp,
                    modifier = Modifier.fillMaxSize(),
                )
                TopBar(
                    state = state,
                    angle = angle,
                    unreadableNameLabel = unreadableNameLabel,
                    onClose = onClose,
                    onSwitchQuality = onSwitchQuality,
                    onToggleSound = onToggleSound,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(FadeColor, Color.Transparent)))
                        // Measured with the status bar's inset in it, so a photo's box starts below
                        // both.
                        .onSizeChanged { barHeight = it.height }
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, FadeColor)))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                ) {
                    BelowViewfinder(state = state, onSetMode = onSetMode, onRequestMicrophone = onRequestMicrophone)
                    BottomRow(controls)
                }
            }
            // The wide-window fallback, where the portrait hold was refused: the display turns, so
            // the icons stay upright as drawn and the controls stand beside the viewfinder.
            CameraLayout.Beside -> Row(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(
                        state = state,
                        angle = angle,
                        unreadableNameLabel = unreadableNameLabel,
                        onClose = onClose,
                        onSwitchQuality = onSwitchQuality,
                        onToggleSound = onToggleSound,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ViewfinderArea(
                        state = state,
                        layout = layout,
                        blinks = blinks,
                        preview = preview,
                        onZoom = onZoom,
                        onFocus = onFocus,
                        onAllow = onAllow,
                        onOpenSettings = onOpenSettings,
                        topInset = 0.dp,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    BelowViewfinder(state = state, onSetMode = onSetMode, onRequestMicrophone = onRequestMicrophone)
                }
                SideColumn(controls)
            }
        }
    }
}

/** What the bottom row and the side column need, gathered so the two arrangements cannot drift. */
private class Controls(
    val state: CameraState,
    val angle: Float,
    val blinks: Int,
    val lastShot: @Composable (itemId: ByteArray, modifier: Modifier) -> Unit,
    val onShoot: () -> Unit,
    val onFlip: () -> Unit,
    val onCycleFlash: () -> Unit,
    val onToggleTorch: () -> Unit,
)

/**
 * The icons' current angle, turned to [iconTurn]'s target for each new [tilt] — animated the short
 * way round, or snapped under reduced motion and in the wide fallback.
 */
@Composable
private fun rememberIconAngle(tilt: Int, layout: CameraLayout): Float {
    val reduced = prefersReducedMotion()
    val duration = DarkChamberTheme.motion.motionBase
    val angle = remember { Animatable(0f) }
    LaunchedEffect(tilt, layout, reduced) {
        val turn = iconTurn(current = angle.value, tilt = tilt, layout = layout, reducedMotion = reduced)
        if (turn.animated) angle.animateTo(turn.degrees, tween(durationMillis = duration)) else angle.snapTo(turn.degrees)
    }
    return angle.value
}

private fun Modifier.turned(angle: Float): Modifier = graphicsLayer { rotationZ = angle }

@Composable
private fun TopBar(
    state: CameraState,
    angle: Float,
    unreadableNameLabel: String,
    onClose: () -> Unit,
    onSwitchQuality: () -> Unit,
    onToggleSound: () -> Unit,
    modifier: Modifier,
) {
    val spacing = DarkChamberTheme.spacing
    val colors = DarkChamberTheme.colors
    Row(
        modifier = modifier.padding(horizontal = spacing.spaceXxs, vertical = spacing.spaceXxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(spacing.spaceXxl)) {
            CloseMark(
                contentDescription = CameraFormat.closeDescription(),
                modifier = Modifier.size(spacing.spaceLg).turned(angle),
            )
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
                    .padding(end = spacing.spaceXs)
                    .clip(DarkChamberTheme.shapes.small)
                    .background(colors.sealedContainer)
                    .padding(horizontal = spacing.spaceXs, vertical = spacing.spaceXxs),
            )
        }
        state.microphoneToggle?.let { toggle ->
            IconButton(onClick = onToggleSound, modifier = Modifier.size(spacing.spaceXxl)) {
                MicrophoneMark(
                    on = toggle.soundOn,
                    contentDescription = CameraFormat.soundDescription(toggle),
                    modifier = Modifier.size(spacing.spaceLg).turned(angle),
                )
            }
        }
        state.qualityChip?.let { chip ->
            // A 48 dp target around a small label: the chip is a control, and a thumb is not a
            // label-sized thing.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(spacing.spaceXxl)
                    .clickable(role = Role.Button, onClick = onSwitchQuality)
                    .semantics { contentDescription = CameraFormat.qualityDescription(chip.current) },
            ) {
                Text(
                    text = CameraFormat.qualityLabel(chip.current),
                    style = DarkChamberTheme.typography.label,
                    color = colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .turned(angle)
                        .border(1.dp, colors.outline, DarkChamberTheme.shapes.small)
                        .padding(horizontal = spacing.spaceXs, vertical = spacing.spaceXxs),
                )
            }
        }
    }
}

/**
 * The viewfinder at the capture's shape, or what stands in its place. The box's shape follows the
 * mode — animated between the two, snapped under reduced motion — and is long side vertical while
 * the screen holds portrait, horizontal in the wide fallback.
 */
@Composable
private fun ViewfinderArea(
    state: CameraState,
    layout: CameraLayout,
    blinks: Int,
    preview: @Composable (Modifier) -> Unit,
    onZoom: (Float) -> Unit,
    onFocus: (x: Float, y: Float) -> Unit,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    topInset: Dp,
    modifier: Modifier,
) {
    val reduced = prefersReducedMotion()
    val long = state.captureRatio.longOverShort
    val target = if (layout == CameraLayout.Below) 1f / long else long
    val ratio by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduced) snap() else tween(durationMillis = DarkChamberTheme.motion.motionBase),
    )
    val inset by animateFloatAsState(
        targetValue = topInset.value,
        animationSpec = if (reduced) snap() else tween(durationMillis = DarkChamberTheme.motion.motionBase),
    )
    Box(
        modifier = modifier.padding(top = inset.dp),
        contentAlignment = if (topInset > 0.dp) Alignment.TopCenter else Alignment.Center,
    ) {
        if (state.showsViewfinder) {
            Viewfinder(
                zoom = state.zoom,
                blinks = blinks,
                preview = preview,
                onZoom = onZoom,
                onFocus = onFocus,
                modifier = Modifier.aspectRatio(ratio),
            )
        } else {
            Explainer(explainer = state.explainer, onAllow = onAllow, onOpenSettings = onOpenSettings)
        }
    }
}

/**
 * Under the viewfinder: the notice line, the microphone hint, and the mode labels — or, while
 * recording, the status in their place.
 */
@Composable
private fun BelowViewfinder(state: CameraState, onSetMode: (CaptureMode) -> Unit, onRequestMicrophone: () -> Unit) {
    val spacing = DarkChamberTheme.spacing
    val colors = DarkChamberTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.spaceMd),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.spaceXxs),
    ) {
        state.notice?.let { notice ->
            Text(
                text = CameraFormat.notice(notice),
                style = DarkChamberTheme.typography.body,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.spaceXxs),
            )
        }
        state.microphoneHint?.let { hint ->
            CameraFormat.microphoneHint(hint)?.let { copy ->
                Text(
                    text = copy.text,
                    style = DarkChamberTheme.typography.caption,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                copy.action?.let { action ->
                    TextButton(onClick = onRequestMicrophone) {
                        Text(text = action, style = DarkChamberTheme.typography.label, color = colors.accent)
                    }
                }
            }
        }
        when (state.row) {
            CameraRow.ModeLabels -> if (state.showsViewfinder) ModeLabels(selected = state.mode, onSetMode = onSetMode)
            CameraRow.Status -> state.recorder.active?.let { RecordingStatus(it, noSound = state.showsNoSoundMark) }
        }
    }
}

/** PHOTO · VIDEO, tapped to switch; the selected one in accent. Text, so it never turns. */
@Composable
private fun ModeLabels(selected: CaptureMode, onSetMode: (CaptureMode) -> Unit) {
    val spacing = DarkChamberTheme.spacing
    val colors = DarkChamberTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.spaceXs)) {
        for (mode in CaptureMode.entries) {
            val isSelected = mode == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .widthIn(min = spacing.spaceXxl * 2)
                    .clip(DarkChamberTheme.shapes.small)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSetMode(mode) })
                    .padding(horizontal = spacing.spaceSm, vertical = spacing.spaceSm),
            ) {
                Text(
                    text = CameraFormat.modeLabel(mode).uppercase(),
                    style = DarkChamberTheme.typography.label,
                    color = if (isSelected) colors.accent else colors.textSecondary,
                )
            }
        }
    }
}

/** ● 02:14 · 1.2 GB recorded · 3.4 GB left, and the no-sound mark when the clip has none. */
@Composable
private fun RecordingStatus(active: ActiveRecording, noSound: Boolean) {
    val spacing = DarkChamberTheme.spacing
    val colors = DarkChamberTheme.colors
    val progress by active.progress.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.spaceXs),
        modifier = Modifier.padding(vertical = spacing.spaceSm),
    ) {
        // A rounded square, not a dot: the full circle is the aperture's alone (docs/DESIGN.md §4).
        Box(modifier = Modifier.size(StatusMarkSize).clip(RoundedCornerShape(2.dp)).background(colors.accent))
        Text(
            text = CameraFormat.recordingStatus(progress, active.limitBytes),
            style = DarkChamberTheme.typography.label,
            color = colors.textPrimary,
            maxLines = 1,
        )
        if (noSound) {
            MicrophoneMark(
                on = false,
                contentDescription = CameraFormat.noSoundDescription(),
                tint = colors.textSecondary,
                modifier = Modifier.size(spacing.spaceMd),
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
    modifier: Modifier,
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
        modifier = modifier
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
 * Last-shot tile at the start edge · shutter in the centre · light at the end edge, with flip midway
 * in the gap between shutter and light. The two halves either side of the shutter share the width
 * equally, so the shutter stays centred; a control that is not offered is left out and its place
 * kept, so nothing else moves either.
 */
@Composable
private fun BottomRow(controls: Controls) {
    val spacing = DarkChamberTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.spaceMd, vertical = spacing.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            LastShotTile(controls)
        }
        Shutter(shutter = controls.state.shutter, blinks = controls.blinks, onShoot = controls.onShoot)
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.weight(1f))
            FlipControl(controls)
            Spacer(modifier = Modifier.weight(1f))
            LightControl(controls)
        }
    }
}

/**
 * The bottom row turned on its side, for a wide window: light at the top edge, shutter in the centre,
 * last-shot tile at the bottom edge, and flip midway in the gap between light and shutter.
 */
@Composable
private fun SideColumn(controls: Controls) {
    val spacing = DarkChamberTheme.spacing
    Column(
        modifier = Modifier.fillMaxHeight().padding(horizontal = spacing.spaceSm, vertical = spacing.spaceMd),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            LightControl(controls)
            Spacer(modifier = Modifier.weight(1f))
            FlipControl(controls)
            Spacer(modifier = Modifier.weight(1f))
        }
        Shutter(shutter = controls.state.shutter, blinks = controls.blinks, onShoot = controls.onShoot)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
            LastShotTile(controls)
        }
    }
}

/**
 * The light control — the flash in photo, the torch in video — or its empty place where the lens has
 * no light or nothing is showing.
 */
@Composable
private fun LightControl(controls: Controls) {
    val spacing = DarkChamberTheme.spacing
    val state = controls.state
    Box(modifier = Modifier.size(spacing.spaceXxl)) {
        val light = state.light
        if (light != null && state.showsViewfinder) {
            when (light) {
                is LightControl.Flash -> IconButton(onClick = controls.onCycleFlash, modifier = Modifier.size(spacing.spaceXxl)) {
                    FlashMark(
                        glyph = flashGlyph(light.mode),
                        contentDescription = CameraFormat.flashDescription(light.mode),
                        modifier = Modifier.size(spacing.spaceLg).turned(controls.angle),
                    )
                }
                is LightControl.Torch -> IconButton(onClick = controls.onToggleTorch, modifier = Modifier.size(spacing.spaceXxl)) {
                    TorchMark(
                        on = light.on,
                        contentDescription = CameraFormat.torchDescription(light.on),
                        modifier = Modifier.size(spacing.spaceLg).turned(controls.angle),
                    )
                }
            }
        }
    }
}

/** The flip control, or its empty place where there is one lens, while recording, or nothing is showing. */
@Composable
private fun FlipControl(controls: Controls) {
    val spacing = DarkChamberTheme.spacing
    Box(modifier = Modifier.size(spacing.spaceXxl)) {
        if (controls.state.flipOffered && controls.state.showsViewfinder) {
            IconButton(onClick = controls.onFlip, modifier = Modifier.size(spacing.spaceXxl)) {
                SwitchCameraMark(
                    contentDescription = CameraFormat.flipDescription(),
                    modifier = Modifier.size(spacing.spaceLg).turned(controls.angle),
                )
            }
        }
    }
}

/**
 * The newest capture this camera has saved, drawn by the host's slot through the session's loader,
 * with an accent ring round it while a clip is still being saved — filling as the encrypt goes, or
 * whole when there is nothing to measure. **Display-only**: no click, no ripple, nothing that reads
 * as a control — there is no viewer to open yet, and a tile that looks tappable and does nothing is a
 * small lie.
 */
@Composable
private fun LastShotTile(controls: Controls) {
    val colors = DarkChamberTheme.colors
    val shape = DarkChamberTheme.shapes.medium
    val radius = DarkChamberTheme.shapes.radiusMd
    val tile = controls.state.tile
    val description = if (tile.saving) {
        CameraFormat.savingDescription(tile.progress)
    } else {
        CameraFormat.lastShotDescription(tile.lastShot)
    }
    Box(
        modifier = Modifier
            .size(TileSize)
            .turned(controls.angle)
            .semantics { contentDescription = description },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(colors.surfaceRaised)
                .border(1.dp, colors.outline, shape),
        ) {
            tile.lastShot?.let { controls.lastShot(it.itemId, Modifier.fillMaxSize()) }
        }
        if (tile.saving) SavingRing(progress = tile.progress, radius = radius)
    }
}

/** The tile's border traced in accent as far as the save has got — the tile's own shape, not a circle. */
@Composable
private fun SavingRing(progress: Float?, radius: Dp) {
    val accent = DarkChamberTheme.colors.accent
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = RingWidth.toPx()
        val inset = stroke / 2f
        val outline = Path().apply {
            addRoundRect(
                RoundRect(
                    left = inset,
                    top = inset,
                    right = size.width - inset,
                    bottom = size.height - inset,
                    cornerRadius = CornerRadius(radius.toPx()),
                ),
            )
        }
        val traced = if (progress == null) {
            outline
        } else {
            val measure = PathMeasure().apply { setPath(outline, forceClosed = true) }
            Path().also { measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), it, startWithMoveTo = true) }
        }
        drawPath(traced, color = accent, style = Stroke(width = stroke))
    }
}

/**
 * The aperture as shutter (docs/DESIGN.md §7): the one full-circle element on the screen, in accent
 * throughout. For a photo it is the ring alone, and a press blinks the iris shut and open again; ready
 * to record it holds an accent dot; recording, the dot becomes a rounded stop square and the ring
 * pulses slowly — steady under reduced motion. Disabled, it is drawn dimmed and does not respond.
 */
@Composable
private fun Shutter(shutter: Shutter, blinks: Int, onShoot: () -> Unit) {
    val motion = DarkChamberTheme.motion
    val accent = DarkChamberTheme.colors.accent
    var shut by remember { mutableStateOf(false) }
    LaunchedEffect(blinks) {
        if (blinks == 0) return@LaunchedEffect
        shut = true
        delay(motion.motionFast.toLong())
        shut = false
    }
    val pulsing = shutter.kind == ShutterKind.Stop && !prefersReducedMotion()
    val ringAlpha = if (pulsing) {
        val pulse = rememberInfiniteTransition()
        val value by pulse.animateFloat(
            initialValue = 1f,
            targetValue = PulseLow,
            animationSpec = infiniteRepeatable(tween(durationMillis = PulseMillis), RepeatMode.Reverse),
        )
        value
    } else {
        1f
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ApertureDefaults.Size)
            .clip(CircleShape)
            .clickable(enabled = shutter.enabled, role = Role.Button, onClick = onShoot)
            .alpha(if (shutter.enabled) 1f else DisabledAlpha),
    ) {
        Box(modifier = Modifier.alpha(ringAlpha)) {
            Aperture(
                state = if (shut) ApertureState.Closed else ApertureState.Open,
                contentDescription = CameraFormat.shutterDescription(shutter),
            )
        }
        when (shutter.kind) {
            ShutterKind.Photo -> Unit
            // Inside the aperture, so the one place a full circle belongs.
            ShutterKind.Record -> Box(modifier = Modifier.size(RecordDotSize).clip(CircleShape).background(accent))
            ShutterKind.Stop -> Box(modifier = Modifier.size(StopSquareSize).clip(RoundedCornerShape(StopCorner)).background(accent))
        }
    }
}

private val TileSize = 56.dp
private val RingWidth = 3.dp
private val FocusSize = 64.dp
private val StatusMarkSize = 8.dp
private val RecordDotSize = 26.dp
private val StopSquareSize = 20.dp
private val StopCorner = 4.dp
private const val FocusShownMillis = 1_000L
private const val BlinkDepth = 0.7f
private const val DisabledAlpha = 0.38f
private const val PulseLow = 0.75f
private const val PulseMillis = 1_600

// The chrome's fades over a video's edges: dark enough that white text and marks keep their
// contrast over any picture, fading out so the frame is not boxed in.
private val FadeColor = Color.Black.copy(alpha = 0.6f)
