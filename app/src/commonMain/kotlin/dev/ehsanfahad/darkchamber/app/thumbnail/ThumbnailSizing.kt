package dev.ehsanfahad.darkchamber.app.thumbnail

import kotlin.math.max
import kotlin.math.roundToInt

/** The pixel dimensions a thumbnail should end up at. */
internal data class ThumbnailSize(val width: Int, val height: Int)

/**
 * The size a thumbnail of a [sourceWidth] × [sourceHeight] image should be: aspect ratio preserved,
 * longest edge at most [maxEdgePx], and **never upscaled** — blowing a small image up costs storage
 * and invents detail the tile can produce for free by stretching.
 *
 * Returns `null` when the source dimensions or the limit are not positive. That is not a defensive
 * check: `BitmapFactory` reports `0` for bounds it could not parse and ImageIO omits the keys
 * entirely, so both platforms arrive here with "I could not read this image" expressed as a
 * non-positive number, and both mean there is no thumbnail to make.
 *
 * Shared between the platform encoders so a thumbnail of the same photo is the same shape whichever
 * device produced it — and because this is the part of the decode that a host test can reach.
 */
internal fun thumbnailTargetSize(sourceWidth: Int, sourceHeight: Int, maxEdgePx: Int): ThumbnailSize? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || maxEdgePx <= 0) return null

    val longestEdge = max(sourceWidth, sourceHeight)
    if (longestEdge <= maxEdgePx) return ThumbnailSize(sourceWidth, sourceHeight)

    val scale = maxEdgePx.toDouble() / longestEdge
    // coerceAtLeast(1): a panorama's short edge rounds to zero, and a zero-dimension bitmap is a
    // decoder crash rather than a very small thumbnail.
    return ThumbnailSize(
        width = (sourceWidth * scale).roundToInt().coerceAtLeast(1),
        height = (sourceHeight * scale).roundToInt().coerceAtLeast(1),
    )
}

/**
 * The power-of-two subsampling factor to decode a [sourceWidth] × [sourceHeight] image at, so the
 * decoder hands back the smallest bitmap that is still at least [target] on **both** edges.
 *
 * Power of two because `BitmapFactory.Options.inSampleSize` rounds anything else down to one, so a
 * non-power-of-two is a silent lie about the dimensions that come back. At least the target on both
 * edges because the remaining exact scale-down must never be a scale *up* — that would spend memory
 * to produce a blurrier tile.
 */
internal fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, target: ThumbnailSize): Int {
    var sample = 1
    while (
        sourceWidth / (sample * 2) >= target.width &&
        sourceHeight / (sample * 2) >= target.height
    ) {
        sample *= 2
    }
    return sample
}
