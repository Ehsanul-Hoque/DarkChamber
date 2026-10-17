package dev.ehsanfahad.darkchamber.app.root

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The rule behind "press back twice to leave", on its own.
 *
 * A pure function of **how long ago the first press was** and how long the offer lasts, so the
 * decision can be checked without a dispatcher, a coroutine or a clock. That split is deliberate:
 * the notice disappearing is cosmetic and runs on a timer, while *this* is what decides whether a
 * press ends the session — and it must not be able to go wrong because a coroutine did not run.
 */
class LeaveAtRootTest {

    @Test
    fun a_first_press_never_leaves() {
        assertFalse(backPressLeaves(armedFor = null, window = WINDOW), "nothing armed it, so nothing leaves")
    }

    @Test
    fun a_press_inside_the_window_leaves() {
        assertTrue(backPressLeaves(armedFor = Duration.ZERO, window = WINDOW), "two presses in one moment")
        assertTrue(backPressLeaves(armedFor = WINDOW / 2, window = WINDOW))
        assertTrue(backPressLeaves(armedFor = WINDOW, window = WINDOW), "the edge is inside the offer")
    }

    @Test
    fun a_press_after_the_window_does_not_leave() {
        assertFalse(backPressLeaves(armedFor = WINDOW + 1.milliseconds, window = WINDOW))
        assertFalse(backPressLeaves(armedFor = 1.seconds, window = Duration.ZERO))
    }

    /**
     * **A clock that went backwards must not leave either.** The elapsed time comes from a monotonic
     * source, so this cannot happen in production — but the reason the source is monotonic is exactly
     * that a wall clock *can* jump, and a negative elapsed time read as "inside the window" would
     * turn one press into leaving.
     */
    @Test
    fun a_negative_elapsed_time_does_not_leave() {
        assertFalse(backPressLeaves(armedFor = -1.seconds, window = WINDOW))
    }

    private companion object {
        val WINDOW: Duration = 2.seconds
    }
}
