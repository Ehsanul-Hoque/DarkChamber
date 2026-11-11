package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * The viewer's zoom and pan arithmetic — every decision the gestures make, kept out of the composable
 * so it can be tested.
 *
 * The model: the picture is drawn fitted to the box, then scaled by `zoom` about the box's centre and
 * moved by `offset`. So a screen point `s` shows the fitted picture's point `(s - center - offset) /
 * zoom`. Zoom runs from 1 (fitted) to [MAX]; the decode is about twice the screen, so 4x is past the
 * decoded detail but still useful for reading small print.
 */
internal object ViewerZoom {
    const val MAX = 4f
    const val DOUBLE_TAP = 2.5f

    /** The size [image] is drawn at when fitted inside [box], shape kept — enlarged if smaller. */
    fun fitted(image: Size, box: Size): Size {
        if (image.width <= 0f || image.height <= 0f) return Size.Zero
        val scale = minOf(box.width / image.width, box.height / image.height)
        return Size(image.width * scale, image.height * scale)
    }

    /** A pinch by [factor], kept between fitted and [MAX]. */
    fun afterPinch(zoom: Float, factor: Float): Float = (zoom * factor).coerceIn(1f, MAX)

    /** A double tap zooms in from fitted, and back to fitted from anywhere else. */
    fun afterDoubleTap(zoom: Float): Float = if (zoom > 1f) 1f else DOUBLE_TAP

    /**
     * The offset after a pinch from [zoom] to [newZoom] about [centroid], moved by [pan]: whatever was
     * under the fingers stays under them. Pass the zoom actually reached (after the clamp), or the
     * picture would drift at the limits.
     */
    fun offsetAfterPinch(offset: Offset, zoom: Float, newZoom: Float, centroid: Offset, center: Offset, pan: Offset): Offset {
        val factor = newZoom / zoom
        return (centroid - center) * (1f - factor) + offset * factor + pan
    }

    /** The offset that brings the point double-tapped at fit to the centre at [newZoom], before clamping. */
    fun offsetForDoubleTapAt(tap: Offset, center: Offset, newZoom: Float): Offset = (center - tap) * newZoom

    /**
     * [offset] kept within the picture: on each axis it may move by at most how far the zoomed picture
     * overhangs the box, so no black shows past an edge that has picture beyond it, and an axis the
     * picture does not overhang does not move.
     */
    fun clampOffset(offset: Offset, zoom: Float, content: Size, box: Size): Offset {
        val limitX = ((content.width * zoom - box.width) / 2f).coerceAtLeast(0f)
        val limitY = ((content.height * zoom - box.height) / 2f).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-limitX, limitX), offset.y.coerceIn(-limitY, limitY))
    }

    /** Zoomed in, a drag pans the picture; only at fit does it turn the page. */
    fun pagerMaySwipe(zoom: Float): Boolean = zoom <= 1f
}
