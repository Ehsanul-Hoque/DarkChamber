package dev.ehsanfahad.darkchamber.feature.vault.presentation

/**
 * A point the thumb is pinned to: [scrolledPx] down the column, the thumb stood at [fraction] of its track.
 * Holds pixels and a fraction only.
 */
internal data class ThumbAnchor(
    val scrolledPx: Float,
    val fraction: Float,
)

/**
 * Where a text page's fast-scroll thumb stands while the column's height is still being estimated.
 *
 * A row's height is known only once it is laid out; until then it is estimated ([RowHeights]). Each
 * correction changes the estimated distance scrolled and the column's height, and a thumb placed by
 * "scrolled ÷ scrollable" would jump with it. So when an estimate changes under a shown thumb, the thumb is
 * pinned where it stands ([ThumbAnchor]) and the rest of the track is spread over the rest of the column:
 * below the pin in proportion to the end, above it in proportion to the top. The top is always 0 and the
 * end always 1. With no pin (a thumb that is hidden, whose place nobody sees change) it is the plain
 * fraction of pixels.
 */
internal object ThumbPlace {
    /** The thumb's fraction for [scrolled] px down a column that scrolls [maxScroll] px, pinned to [anchor]. */
    fun fraction(
        scrolled: Float,
        maxScroll: Float,
        anchor: ThumbAnchor?,
    ): Float {
        // A column that cannot scroll is at its top when nothing is scrolled, and at its end otherwise.
        if (scrolled <= 0f) return 0f
        if (scrolled >= maxScroll) return 1f
        val pin = anchor?.takeIf { usable(it, maxScroll) } ?: return scrolled / maxScroll
        return if (scrolled < pin.scrolledPx) {
            pin.fraction * scrolled / pin.scrolledPx
        } else {
            pin.fraction + (1f - pin.fraction) * (scrolled - pin.scrolledPx) / (maxScroll - pin.scrolledPx)
        }
    }

    /**
     * The distance down the column that [fraction] (0..1, as a drag gives it) stands for — the inverse of
     * [fraction]. Both ends fall out of the shares: 0 is the top, 1 the end.
     */
    fun scrolledAt(
        fraction: Float,
        maxScroll: Float,
        anchor: ThumbAnchor?,
    ): Float {
        val pin = anchor?.takeIf { usable(it, maxScroll) } ?: return fraction * maxScroll
        return if (fraction < pin.fraction) {
            pin.scrolledPx * fraction / pin.fraction
        } else {
            pin.scrolledPx + (maxScroll - pin.scrolledPx) * (fraction - pin.fraction) / (1f - pin.fraction)
        }
    }

    /**
     * The pin after an estimate changed: the thumb stays at [shownBefore], the fraction it showed, now at
     * [scrolledAfter] px — the new estimate of how far down it is. A thumb not [shown] keeps no pin.
     */
    fun repin(
        shown: Boolean,
        shownBefore: Float,
        scrolledAfter: Float,
    ): ThumbAnchor? = if (shown) ThumbAnchor(scrolledAfter, shownBefore) else null

    /** A pin strictly inside the column and the track; at or past an end there is no rest to share out. */
    private fun usable(
        pin: ThumbAnchor,
        maxScroll: Float,
    ): Boolean = pin.scrolledPx > 0f && pin.scrolledPx < maxScroll && pin.fraction > 0f && pin.fraction < 1f
}
