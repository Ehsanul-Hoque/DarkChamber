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
        assertTrue(arrival.fadeIn > 0, "a fade still has to say which tile just landed")
        assertTrue(arrival.fadeOut > 0)
    }
}
