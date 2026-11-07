package dev.ehsanfahad.darkchamber.app.image

/**
 * The power-of-two subsampling factor to decode a [sourceWidth] × [sourceHeight] image at for the
 * viewer: the smallest one whose decoded long edge is at most [maxLongEdge].
 *
 * **Not the thumbnail rule, on purpose.** A thumbnail decodes at least its target on both edges and
 * then scales down exactly; for a tile that is cheap. For the viewer the same rule would decode a
 * 48 MP photo at full size — about 192 MB of pixels — only to scale it to a quarter. Rounding the
 * factor up instead gives a decoded image between half the target and the target, with no scaling
 * step and no second full-size copy. The target is twice the screen, so even the low end is the
 * screen's own size.
 *
 * Power of two because that is what `inSampleSize` honours exactly; the decoded edge is the source
 * edge divided and rounded **up**, which is what the fit is checked against.
 *
 * `null` for bounds the platform could not parse — reported as zero or less — or no target: there is
 * nothing to decode.
 */
internal fun viewerSampleSize(sourceWidth: Int, sourceHeight: Int, maxLongEdge: Int): Int? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || maxLongEdge <= 0) return null
    val longEdge = maxOf(sourceWidth, sourceHeight)
    var sample = 1
    while ((longEdge + sample - 1) / sample > maxLongEdge) sample *= 2
    return sample
}
