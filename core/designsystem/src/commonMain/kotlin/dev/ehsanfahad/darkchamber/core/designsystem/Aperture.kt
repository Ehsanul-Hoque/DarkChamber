package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The aperture — DarkChamber's one signature element (docs/DESIGN.md §7). A circular
 * iris that is *open* when the vault is unlocked and *closed* when locked; it doubles
 * as the honest session light (open iris = session live). This is the only element in
 * the app that uses the full-circle radius (§4), which is what makes it read as the brand.
 *
 * The [ApertureState] → open-fraction mapping is a pure function ([ApertureState.openFraction])
 * so it is unit-testable without a composition; the composable animates toward it.
 */
enum class ApertureState {
    /** Vault locked — iris shut. */
    Closed,

    /** Vault unlocked — iris fully open (session live). */
    Open,

    /** Keys unwrapping — iris opening from closed. */
    Unlocking,

    /** Unlock failed — iris stays shut (the UI adds the 2 px shake, §7). */
    Failed,
}

/** How far the iris is open, 0f (shut) … 1f (fully open). Pure — no composition needed. */
fun ApertureState.openFraction(): Float = when (this) {
    ApertureState.Closed -> 0f
    ApertureState.Open -> 1f
    ApertureState.Unlocking -> 0.6f
    ApertureState.Failed -> 0f
}

/** Non-composable aperture geometry defaults, so sizes are testable without a composition. */
object ApertureDefaults {
    /** The shutter-button / lock-indicator diameter (§7: 72 dp shutter). */
    val Size: Dp = 72.dp

    /** The accent iris-ring stroke width. */
    val RingWidth: Dp = 3.dp
}

/**
 * Draws the aperture. [state] drives the iris; the opening animates over
 * [DarkChamberMotion.motionSlow] (lock/unlock iris, §8). [contentDescription] is
 * REQUIRED — the aperture is a custom drawing and must carry a label for
 * TalkBack/VoiceOver (§12).
 */
@Composable
fun Aperture(
    state: ApertureState,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = ApertureDefaults.Size,
    ringWidth: Dp = ApertureDefaults.RingWidth,
) {
    val colors = DarkChamberTheme.colors
    val motion = DarkChamberTheme.motion
    val open by animateFloatAsState(
        targetValue = state.openFraction(),
        animationSpec = androidx.compose.animation.core.tween(durationMillis = motion.motionSlow),
        label = "apertureOpen",
    )

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val diameter = this.size.minDimension
        val center = this.center
        val ringStroke = ringWidth.toPx()
        val outerRadius = (diameter - ringStroke) / 2f

        // The amber iris ring — the fixed rim of the aperture.
        drawCircle(
            color = colors.accent,
            radius = outerRadius,
            center = center,
            style = Stroke(width = ringStroke),
        )

        // The iris itself: a filled disc that shrinks as the aperture opens. Fully
        // closed (open = 0) fills the rim; fully open (open = 1) collapses to nothing,
        // leaving the hollow bore that reads as "unlocked".
        val irisRadius = outerRadius * (1f - open)
        if (irisRadius > 0f) {
            drawCircle(
                color = colors.accent,
                radius = irisRadius,
                center = center,
            )
        }
    }
}
