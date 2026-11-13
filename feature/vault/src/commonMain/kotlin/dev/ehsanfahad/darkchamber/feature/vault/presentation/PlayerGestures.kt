package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

/**
 * The player page's gesture map: which half of the page a touch lands on, what each gesture there does,
 * and who owns a drag — the pager, a level slide, the seek bar, or nobody. Kept out of the composable so
 * it can be tested; the composable only measures and reports.
 *
 * The page is split down the middle. The left half is the "less" side — brightness, back ten seconds,
 * half speed — and the right half the "more" side — volume, forward ten seconds, double speed. An audio
 * page has no picture, so it has no brightness, and its controls never hide.
 */
internal object PlayerGestures {
    /** The half of a page [width] wide that [x] falls in; the middle line belongs to the right. */
    fun half(x: Float, width: Float): Half = if (x < width / 2f) Half.Left else Half.Right

    /** A single tap shows or hides the controls on a video page; an audio page's controls stay. */
    fun onTap(kind: PlayerKind): GestureAction = when (kind) {
        PlayerKind.Video -> GestureAction.ToggleControls
        PlayerKind.Audio -> GestureAction.None
    }

    /** A double tap moves ten seconds: back on the left half, forward on the right. */
    fun onDoubleTap(x: Float, area: GestureArea): GestureAction = when (half(x, area.width)) {
        Half.Left -> GestureAction.Skip(-PlaybackRules.SKIP_MS)
        Half.Right -> GestureAction.Skip(PlaybackRules.SKIP_MS)
    }

    /** Holding a half plays at half speed on the left and double speed on the right, while held. */
    fun onHold(x: Float, area: GestureArea): GestureAction = when (half(x, area.width)) {
        Half.Left -> GestureAction.Hold(HeldSpeed.Slow)
        Half.Right -> GestureAction.Hold(HeldSpeed.Fast)
    }

    /**
     * Who a drag belongs to, decided once from where it started and its first movement:
     * - one that starts on the seek bar is the seek bar's, whichever way it goes, so dragging the seek
     *   bar never turns the page;
     * - otherwise one that starts more sideways than up or down is the pager's;
     * - one that starts up or down is a level slide — brightness on the left, volume on the right — only
     *   if it started between the bars, away from the system's own edge gestures; an audio page has no
     *   brightness. Anything else belongs to nobody, and the pager does not move for it either.
     */
    fun dragOwner(kind: PlayerKind, start: Offset, firstMove: Offset, area: GestureArea): DragOwner {
        if (area.seekBar?.contains(start) == true) return DragOwner.SeekBar
        if (abs(firstMove.x) >= abs(firstMove.y)) return DragOwner.Pager
        if (start.y < area.bandTop || start.y >= area.bandBottom) return DragOwner.Nobody
        return when (half(start.x, area.width)) {
            Half.Left -> if (kind == PlayerKind.Video) DragOwner.Brightness else DragOwner.Nobody
            Half.Right -> DragOwner.Volume
        }
    }

    /**
     * A level (brightness or volume, 0 to 1) after a slide of [dy] pixels: sliding the band's whole
     * height up goes from nothing to full. Screen y grows downward, so up is a negative [dy].
     */
    fun levelAfterSlide(level: Float, dy: Float, area: GestureArea): Float {
        val band = area.bandBottom - area.bandTop
        if (band <= 0f) return level
        return (level - dy / band).coerceIn(0f, 1f)
    }
}

internal enum class PlayerKind { Video, Audio }

internal enum class Half { Left, Right }

/**
 * The page as the gestures see it: its [width], the band between the bars ([bandTop] to [bandBottom])
 * where level slides may start, and the seek bar's bounds while it is on screen.
 */
internal data class GestureArea(
    val width: Float,
    val bandTop: Float,
    val bandBottom: Float,
    val seekBar: Rect?,
)

internal sealed interface GestureAction {
    data object None : GestureAction

    data object ToggleControls : GestureAction

    /** Move by [byMs]: negative is back. */
    data class Skip(val byMs: Long) : GestureAction

    /** Run at [speed] until the finger lifts. */
    data class Hold(val speed: HeldSpeed) : GestureAction
}

internal enum class DragOwner { Pager, Brightness, Volume, SeekBar, Nobody }
