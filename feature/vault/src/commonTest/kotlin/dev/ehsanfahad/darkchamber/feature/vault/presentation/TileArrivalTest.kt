package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The grid's arrival motion. The animation itself needs a device to judge; what can be pinned here
 * is that it comes from the design tokens and that the reduced-motion setting actually changes it.
 */
class TileArrivalTest {
    private val motion = DarkChamberMotion()

    @Test
    fun durations_come_from_the_design_tokens_not_from_literals() {
        val arrival = tileArrival(motion, reducedMotion = false)

        assertEquals(motion.motionBase, arrival.fadeIn)
        assertEquals(motion.motionBase, arrival.placement)
        assertEquals(motion.motionFast, arrival.fadeOut, "leaving should get out of the way faster")
    }

    @Test
    fun a_custom_motion_scale_flows_through_rather_than_being_ignored() {
        // A theme that slows or quickens motion must reach the grid; a hardcoded tween here would
        // pass the test above and still fail this one.
        val slower = DarkChamberMotion(motionFast = 60, motionBase = 500, motionSlow = 900)

        val arrival = tileArrival(slower, reducedMotion = false)

        assertEquals(500, arrival.fadeIn)
        assertEquals(500, arrival.placement)
        assertEquals(60, arrival.fadeOut)
    }

    /**
     * DESIGN.md §8: motion is *replaced* with cross-fades under reduced motion, not removed. The
     * movement is the part that troubles someone who turned the setting on — and with newest-first
     * ordering every existing tile shifts on each import, so there is a lot of it.
     */
    @Test
    fun reduced_motion_drops_the_movement_but_keeps_the_fade() {
        val arrival = tileArrival(motion, reducedMotion = true)

        assertNull(arrival.placement, "tiles must not slide when motion is reduced")
        assertNotNull(arrival.fadeIn)
        assertNotNull(arrival.fadeOut)
        assertTrue(arrival.fadeIn > 0, "a fade still has to say which tile just landed")
        assertTrue(arrival.fadeOut > 0)
    }

    /**
     * **Re-ordering is not an arrival, and animating it draws two tiles in one slot.** The animation
     * exists to answer "which tile just landed", which a re-order has no answer to: every tile is the
     * same tile somewhere else. Caught on a slowed screen recording — for about four frames the first
     * row drew two file names and two size lines on top of each other, one fading out while the other
     * faded in, which reads as a flicker.
     *
     * So nothing moves and nothing fades while the listing is being re-ordered — including the fade,
     * which is the part reduced motion keeps. That is the difference between the two cases: reduced
     * motion still has an arrival to report, and a re-order does not.
     */
    @Test
    fun re_ordering_is_not_animated_at_all() {
        val arrival = tileArrival(motion, reducedMotion = false, reordering = true)

        assertNull(arrival.placement, "nothing moves")
        assertNull(arrival.fadeIn, "and nothing fades in over the tile it is replacing")
        assertNull(arrival.fadeOut, "nor out from under it")
    }

    @Test
    fun re_ordering_outranks_the_motion_setting() {
        // Both settings point at "less motion", so the stricter one wins — but the test exists
        // because the obvious implementation branches on reduced motion first and never reaches this.
        val arrival = tileArrival(motion, reducedMotion = true, reordering = true)

        assertNull(arrival.fadeIn)
        assertNull(arrival.fadeOut)
        assertNull(arrival.placement)
    }

    @Test
    fun an_ordinary_listing_change_still_animates() {
        // The guard must be specific to re-ordering: an import has to keep its arrival, which is the
        // whole reason the animation is there.
        val arrival = tileArrival(motion, reducedMotion = false, reordering = false)

        assertNotNull(arrival.fadeIn)
        assertNotNull(arrival.placement)
    }
}
