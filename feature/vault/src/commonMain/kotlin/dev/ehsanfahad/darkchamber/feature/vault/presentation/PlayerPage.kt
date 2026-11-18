package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ehsanfahad.darkchamber.core.designsystem.BrightnessMark
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.core.designsystem.ExcludeFromPlatformStructures
import dev.ehsanfahad.darkchamber.core.designsystem.PauseMark
import dev.ehsanfahad.darkchamber.core.designsystem.PlayMark
import dev.ehsanfahad.darkchamber.core.designsystem.ReplayMark
import dev.ehsanfahad.darkchamber.core.designsystem.RotationMark
import dev.ehsanfahad.darkchamber.core.designsystem.VolumeMark
import dev.ehsanfahad.darkchamber.core.designsystem.prefersReducedMotion
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerKind
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerState
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackNote
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPlayer
import kotlinx.coroutines.delay

/*
 * The video and audio page. Every decision — which half does what, who owns a drag, where a skip lands,
 * what the words say — is made in PlayerGestures, PlaybackRules and ViewerFormat; this only measures,
 * reports and draws.
 */

/** What the player page asks of the viewer. */
internal class PlayerActions(
    val playPause: () -> Unit,
    val seekTo: (Long) -> Unit,
    val skip: (forward: Boolean) -> Unit,
    val holdSpeed: (faster: Boolean) -> Unit,
    val releaseSpeed: () -> Unit,
    val setVolume: (Float) -> Unit,
    /** Where a volume slide starts: the level now; `null` while nothing plays. */
    val volume: () -> Float?,
    /** Where a brightness slide starts; `null` where the brightness cannot be set. */
    val brightness: () -> Float?,
    val setBrightness: (Float) -> Unit,
    val chooseSpeed: (Float) -> Unit,
    val switchOrientation: () -> Unit,
)

/** A brief word over the page about what a gesture just did. */
private sealed interface Feedback {
    val half: Half

    data class Words(val text: String, override val half: Half) : Feedback

    data class Level(val brightness: Boolean, val level: Float, override val half: Half) : Feedback
}

/**
 * One video or audio page: the picture (or the badge, for audio), the note on what this device cannot
 * play, and the gestures. [player] is there only on the page shown; a page beside it is still.
 */
@Composable
internal fun PlayerPageView(
    kind: PlayerKind,
    badge: String,
    note: TrackNote?,
    player: VaultPlayer?,
    actions: PlayerActions,
    onToggleBars: () -> Unit,
    onInteraction: () -> Unit,
) {
    var feedback by remember { mutableStateOf<Feedback?>(null) }
    // A level or a skip shows briefly after the last change; a hold shows while it lasts.
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(feedback, holding) {
        if (feedback != null && !holding) {
            delay(FEEDBACK_MS)
            feedback = null
        }
    }
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        // Level slides start only between the bars' places, clear of the system's own edge gestures.
        val margin = with(density) { BAND_MARGIN.toPx() }
        val area = GestureArea(
            width = width,
            bandTop = insets.getTop(density) + margin,
            bandBottom = height - insets.getBottom(density) - margin,
            seekBar = null, // the seek bar sits over the page, so its drags never reach here
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(kind, area, player) {
                    detectTapGestures(
                        onTap = {
                            if (PlayerGestures.onTap(kind) == GestureAction.ToggleControls) onToggleBars()
                        },
                        onDoubleTap = { at ->
                            if (player == null) return@detectTapGestures
                            val skip = PlayerGestures.onDoubleTap(at.x, area) as GestureAction.Skip
                            val forward = skip.byMs > 0
                            actions.skip(forward)
                            feedback = Feedback.Words(ViewerFormat.skipFeedback(forward), PlayerGestures.half(at.x, width))
                            onInteraction()
                        },
                        onLongPress = { at ->
                            if (player == null) return@detectTapGestures
                            val hold = PlayerGestures.onHold(at.x, area) as GestureAction.Hold
                            holding = true
                            actions.holdSpeed(hold.speed == HeldSpeed.Fast)
                            feedback = Feedback.Words(ViewerFormat.speed(hold.speed.speed), PlayerGestures.half(at.x, width))
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (holding) {
                                holding = false
                                actions.releaseSpeed()
                            }
                        },
                    )
                }
                .pointerInput(kind, area, player) {
                    var owner = DragOwner.Nobody
                    var level = 0f
                    detectVerticalDragGestures(
                        onDragStart = { start ->
                            owner = if (player == null) {
                                DragOwner.Nobody
                            } else {
                                PlayerGestures.dragOwner(kind, start, firstMove = Offset(0f, 1f), area = area)
                            }
                            level = when (owner) {
                                DragOwner.Brightness -> actions.brightness() ?: run {
                                    owner = DragOwner.Nobody
                                    0f
                                }
                                DragOwner.Volume -> actions.volume() ?: run {
                                    owner = DragOwner.Nobody
                                    0f
                                }
                                else -> 0f
                            }
                        },
                        onDragEnd = { owner = DragOwner.Nobody },
                        onDragCancel = { owner = DragOwner.Nobody },
                        onVerticalDrag = { change, dy ->
                            val brightness = owner == DragOwner.Brightness
                            if (!brightness && owner != DragOwner.Volume) return@detectVerticalDragGestures
                            change.consume()
                            level = PlayerGestures.levelAfterSlide(level, dy, area)
                            if (brightness) actions.setBrightness(level) else actions.setVolume(level)
                            feedback = Feedback.Level(brightness, level, if (brightness) Half.Left else Half.Right)
                        },
                    )
                },
        ) {
            when (kind) {
                PlayerKind.Video -> player?.Picture(Modifier.fillMaxSize())
                PlayerKind.Audio -> Text(
                    text = badge,
                    style = DarkChamberTheme.typography.display,
                    color = DarkChamberTheme.colors.textSecondary,
                    // Above the centre control, which is centred too and would otherwise cover it.
                    modifier = Modifier.align(Alignment.Center).layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val shift = PlayerChrome.badgeShiftAboveCentre(
                            badgeHeight = placeable.height,
                            centreSize = CENTRE_SIZE.roundToPx(),
                            gap = BADGE_GAP.roundToPx(),
                        )
                        layout(placeable.width, placeable.height) {
                            // The box centres this layout, so moving the badge by the shift moves it
                            // from the centred position.
                            placeable.place(0, shift)
                        }
                    },
                )
            }
            note?.let { TrackNoteLine(it, Modifier.align(Alignment.TopCenter)) }
            feedback?.let { FeedbackBubble(it, Modifier.align(if (it.half == Half.Left) Alignment.CenterStart else Alignment.CenterEnd)) }
        }
    }
}

/** What this device cannot play of the item, under the top bar's place. */
@Composable
private fun TrackNoteLine(note: TrackNote, modifier: Modifier) {
    Text(
        text = ViewerFormat.trackNote(note),
        style = DarkChamberTheme.typography.caption,
        color = DarkChamberTheme.colors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = NOTE_TOP)
            .background(ScrimDark, DarkChamberTheme.shapes.medium)
            .padding(horizontal = DarkChamberTheme.spacing.spaceSm, vertical = DarkChamberTheme.spacing.spaceXxs),
    )
}

@Composable
private fun FeedbackBubble(feedback: Feedback, modifier: Modifier) {
    val colors = DarkChamberTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = DarkChamberTheme.spacing.spaceXl)
            .background(ScrimDark, DarkChamberTheme.shapes.medium)
            .padding(horizontal = DarkChamberTheme.spacing.spaceSm, vertical = DarkChamberTheme.spacing.spaceXs),
    ) {
        when (feedback) {
            is Feedback.Words -> Text(feedback.text, style = DarkChamberTheme.typography.title, color = colors.textPrimary)
            is Feedback.Level -> {
                if (feedback.brightness) {
                    BrightnessMark(contentDescription = null)
                } else {
                    VolumeMark(muted = feedback.level <= 0f, contentDescription = null)
                }
                Text(
                    text = ViewerFormat.levelFeedback(feedback.level),
                    style = DarkChamberTheme.typography.title,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(start = DarkChamberTheme.spacing.spaceXs),
                )
            }
        }
    }
}

/** Play, pause or play again, in the middle of the page — shown and hidden with the bars. */
@Composable
internal fun CentreControlButton(visible: Boolean, state: PlayerState, onClick: () -> Unit, modifier: Modifier) {
    val duration = if (prefersReducedMotion()) 0 else DarkChamberTheme.motion.motionBase
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration)), modifier = modifier) {
        val control = PlaybackRules.centreControl(PlaybackRules.status(state))
        val words = ViewerFormat.centreControl(control)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(CENTRE_SIZE)
                .background(ScrimDark, DarkChamberTheme.shapes.large)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = words },
        ) {
            val mark = Modifier.size(CENTRE_MARK)
            when (control) {
                CentreControl.Play -> PlayMark(contentDescription = null, modifier = mark)
                CentreControl.Pause -> PauseMark(contentDescription = null, modifier = mark)
                CentreControl.Replay -> ReplayMark(contentDescription = null, modifier = mark)
            }
        }
    }
}

/**
 * The seek bar, the times, the speed setting and — on a video page where the screen can turn — the
 * orientation button. Sits in the bottom bar above Details and Delete.
 */
@Composable
internal fun PlayerControls(
    kind: PlayerKind,
    state: PlayerState,
    speed: Float,
    orientationTarget: ScreenOrientation?,
    actions: PlayerActions,
    onInteraction: () -> Unit,
) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    // While a finger drags the bar, it shows the finger's place, not the player's.
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs
    val shownPosition = if (dragging && duration != null) PlaybackRules.seekPosition(dragFraction, duration) else state.positionMs
    val seekWords = ViewerFormat.seekState(shownPosition, duration)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.spaceMd)) {
        Slider(
            value = if (dragging) dragFraction else PlaybackRules.seekFraction(state.positionMs, duration),
            onValueChange = {
                dragging = true
                dragFraction = it
                onInteraction()
            },
            onValueChangeFinished = {
                if (duration != null) actions.seekTo(PlaybackRules.seekPosition(dragFraction, duration))
                dragging = false
            },
            enabled = duration != null,
            colors = SliderDefaults.colors(
                thumbColor = colors.textPrimary,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.outline,
            ),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = ViewerFormat.seekLabel()
                stateDescription = seekWords
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = ViewerFormat.playTimes(shownPosition, duration),
                style = DarkChamberTheme.typography.caption,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            SpeedSetting(speed = speed, onChoose = actions.chooseSpeed, onInteraction = onInteraction)
            PlayerChrome.orientationButton(kind, orientationTarget)?.let { target ->
                ControlButton(
                    description = ViewerFormat.orientationDescription(target),
                    onClick = {
                        actions.switchOrientation()
                        onInteraction()
                    },
                ) {
                    RotationMark(contentDescription = null)
                    Text(
                        text = ViewerFormat.orientationLabel(target),
                        style = DarkChamberTheme.typography.label,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(start = spacing.spaceXxs),
                    )
                }
            }
        }
    }
}

/** The speed: its pill opens a menu of the speeds on offer. The menu is its own window. */
@Composable
private fun SpeedSetting(speed: Float, onChoose: (Float) -> Unit, onInteraction: () -> Unit) {
    val colors = DarkChamberTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        ControlButton(
            description = ViewerFormat.speedDescription(speed),
            onClick = {
                open = true
                onInteraction()
            },
        ) {
            Text(text = ViewerFormat.speed(speed), style = DarkChamberTheme.typography.label, color = colors.textPrimary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surfaceRaised) {
            // Its own window, so its own exclusion.
            ExcludeFromPlatformStructures()
            for (choice in PlaybackRules.SPEEDS) {
                val chosen = choice == speed
                DropdownMenuItem(
                    text = {
                        Text(
                            text = ViewerFormat.speed(choice),
                            style = DarkChamberTheme.typography.body,
                            color = if (chosen) colors.accent else colors.textPrimary,
                        )
                    },
                    onClick = {
                        onChoose(choice)
                        open = false
                    },
                    modifier = Modifier.semantics { selected = chosen },
                )
            }
        }
    }
}

/** A 48 dp target with a shape of its own over the picture, spoken as [description]. */
@Composable
private fun ControlButton(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = DarkChamberTheme.spacing.spaceXs),
    ) {
        content()
    }
}

private val BAND_MARGIN = 48.dp
private val NOTE_TOP = 64.dp
private val CENTRE_SIZE = 72.dp
private val CENTRE_MARK = 40.dp

/** Between the audio page's badge and the centre control below it. */
private val BADGE_GAP = 16.dp
private const val FEEDBACK_MS = 700L
