package dev.ehsanfahad.darkchamber.feature.camera.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.ehsanfahad.darkchamber.core.designsystem.FlashGlyph
import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode

/** The mark the flash control draws for [mode]. */
internal fun flashGlyph(mode: FlashMode): FlashGlyph =
    when (mode) {
        FlashMode.Off -> FlashGlyph.Off
        FlashMode.Auto -> FlashGlyph.Auto
        FlashMode.On -> FlashGlyph.On
    }

/** Where the camera's controls stand relative to the viewfinder. */
internal enum class CameraLayout { Below, Beside }

/**
 * The controls go beside the viewfinder on a screen wider than it is tall — under it, they would
 * leave a landscape viewfinder a thin strip — and under it otherwise.
 *
 * The camera screen holds portrait, so a wide window is the fallback for where that request is
 * refused: large screens and multi-window. There the display rotates, and the icons do not
 * ([iconTurn]).
 */
internal fun cameraLayout(screen: IntSize): CameraLayout =
    if (screen.width > screen.height) CameraLayout.Beside else CameraLayout.Below

/** An icon's angle to turn to, and whether to animate getting there. */
internal data class IconTurn(val degrees: Float, val animated: Boolean)

/**
 * Where the turning icons go for the device's [tilt] (0, 90, 180 or 270° clockwise), starting from
 * the angle they are at now.
 *
 * Turned **against** the tilt, so an icon stays upright to the person holding the phone while the
 * screen itself holds portrait. **The shortest way round**: the angle keeps counting past ±180°
 * rather than jumping back, so turning from 0° to a 270° tilt is a quarter turn, not three quarters.
 * Under reduced motion they snap. In the wide fallback ([CameraLayout.Beside]) the display rotates
 * on its own, so icons are drawn upright and do not turn.
 */
internal fun iconTurn(current: Float, tilt: Int, layout: CameraLayout, reducedMotion: Boolean): IconTurn {
    if (layout == CameraLayout.Beside) return IconTurn(0f, animated = false)
    val target = -tilt.toFloat()
    // The difference to the target, brought into -180..180.
    val step = ((target - current) % 360f + 540f) % 360f - 180f
    return IconTurn(current + step, animated = !reducedMotion)
}

/**
 * A tap on the viewfinder as fractions of its width and height — the seam's terms, so the camera
 * never needs the preview's pixel size. `null` before the viewfinder has a size.
 */
internal fun focusFractions(tap: Offset, size: IntSize): Pair<Float, Float>? {
    if (size.width <= 0 || size.height <= 0) return null
    return tap.x / size.width to tap.y / size.height
}
