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
 */
internal fun cameraLayout(screen: IntSize): CameraLayout =
    if (screen.width > screen.height) CameraLayout.Beside else CameraLayout.Below

/**
 * A tap on the viewfinder as fractions of its width and height — the seam's terms, so the camera
 * never needs the preview's pixel size. `null` before the viewfinder has a size.
 */
internal fun focusFractions(tap: Offset, size: IntSize): Pair<Float, Float>? {
    if (size.width <= 0 || size.height <= 0) return null
    return tap.x / size.width to tap.y / size.height
}
