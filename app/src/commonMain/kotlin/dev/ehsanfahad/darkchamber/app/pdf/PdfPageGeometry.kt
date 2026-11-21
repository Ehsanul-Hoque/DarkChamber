package dev.ehsanfahad.darkchamber.app.pdf

/**
 * An affine map from a PDF page's own space (points, y up) to a bitmap's pixels (y up, as CoreGraphics
 * draws into one): a point (x, y) goes to (a·x + c·y + tx, b·x + d·y + ty).
 */
internal data class PageTransform(val a: Double, val b: Double, val c: Double, val d: Double, val tx: Double, val ty: Double) {
    fun apply(x: Double, y: Double): Pair<Double, Double> = (a * x + c * y + tx) to (b * x + d * y + ty)
}

/**
 * How a PDF page is shown, from its crop box and its `/Rotate`: what a reader sees is the crop box —
 * not the media box, which may carry bleed and printer's marks — turned clockwise by the rotation. A
 * renderer that draws the raw media box shows a rotated scan sideways and a cropped page with its bleed.
 */
internal object PdfPageGeometry {
    /** A `/Rotate` value as one of 0, 90, 180, 270 — the PDF allows any multiple of 90, negative too. */
    fun rotation(angle: Int): Int = ((angle % 360) + 360) % 360

    /** The page's shown width and height: the crop box's, swapped when it is turned a quarter. */
    fun shownSize(cropWidth: Double, cropHeight: Double, rotation: Int): Pair<Double, Double> =
        if (rotation == 90 || rotation == 270) cropHeight to cropWidth else cropWidth to cropHeight

    /**
     * The map that puts the crop box ([cropX], [cropY], [cropWidth] × [cropHeight]), turned clockwise by
     * [rotation], exactly onto a bitmap [widthPx] × [heightPx].
     */
    fun transform(
        cropX: Double,
        cropY: Double,
        cropWidth: Double,
        cropHeight: Double,
        rotation: Int,
        widthPx: Int,
        heightPx: Int,
    ): PageTransform {
        val (shownWidth, shownHeight) = shownSize(cropWidth, cropHeight, rotation)
        // Points to pixels, each axis on its own: the bitmap's height was rounded from its width.
        val sx = widthPx / shownWidth
        val sy = heightPx / shownHeight
        val right = cropX + cropWidth
        val top = cropY + cropHeight
        return when (rotation) {
            // A quarter clockwise: the page's y runs along the bitmap's x, its x down the bitmap's y.
            90 -> PageTransform(a = 0.0, b = -sy, c = sx, d = 0.0, tx = -sx * cropY, ty = sy * right)
            180 -> PageTransform(a = -sx, b = 0.0, c = 0.0, d = -sy, tx = sx * right, ty = sy * top)
            270 -> PageTransform(a = 0.0, b = sy, c = -sx, d = 0.0, tx = sx * top, ty = -sy * cropX)
            else -> PageTransform(a = sx, b = 0.0, c = 0.0, d = sy, tx = -sx * cropX, ty = -sy * cropY)
        }
    }
}
