package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberMotion

/**
 * How a tile arrives in, moves within, or leaves the grid — durations in milliseconds, and `null`
 * anywhere for "do not animate that at all".
 */
internal data class TileArrival(val fadeIn: Int?, val fadeOut: Int?, val placement: Int?)

/**
 * The arrival motion for a gallery tile, from the design system's tokens (`docs/DESIGN.md` §8).
 *
 * Pulled out of the composable because it is the part with a rule in it, and the part that can
 * quietly regress: a hardcoded duration, or a reduced-motion setting that ends up ignored.
 *
 * **Reduced motion drops the movement, not the feedback.** DESIGN.md says to *replace* motion with
 * cross-fades, not to remove it — with newest-first ordering an import makes every existing tile
 * shift, and that sliding is what troubles someone who turned the setting on. A fade still answers
 * the only question the animation exists for: which tile just landed.
 *
 * **Re-ordering drops all of it, including the fade.** A re-order has no arrival to report — every
 * tile is the same tile somewhere else — and animating one draws two tiles in a single slot while one
 * fades out and the other fades in. On a slowed recording that showed as two file names and two size
 * lines printed over each other in the first row for about four frames, which is what a flicker looks
 * like at full speed. This is the one case that outranks [reducedMotion]: that setting still has an
 * event to soften, and this has none.
 */
internal fun tileArrival(
    motion: DarkChamberMotion,
    reducedMotion: Boolean,
    reordering: Boolean = false,
): TileArrival =
    if (reordering) {
        TileArrival(fadeIn = null, fadeOut = null, placement = null)
    } else {
        TileArrival(
            // Arriving is the event worth noticing; leaving should get out of the way.
            fadeIn = motion.motionBase,
            fadeOut = motion.motionFast,
            placement = if (reducedMotion) null else motion.motionBase,
        )
    }
