package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberMotion

/**
 * How a tile arrives in, moves within, or leaves the grid — durations in milliseconds, and
 * [placement] `null` for "do not animate movement at all".
 */
internal data class TileArrival(val fadeIn: Int, val fadeOut: Int, val placement: Int?)

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
 */
internal fun tileArrival(motion: DarkChamberMotion, reducedMotion: Boolean): TileArrival =
    TileArrival(
        // Arriving is the event worth noticing; leaving should get out of the way.
        fadeIn = motion.motionBase,
        fadeOut = motion.motionFast,
        placement = if (reducedMotion) null else motion.motionBase,
    )
