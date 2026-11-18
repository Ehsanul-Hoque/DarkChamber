package dev.ehsanfahad.darkchamber.app.player

import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a window recreated under the player — by the orientation button, or after a picker — is told. */
class HeldDisplayTest {
    @Test
    fun a_new_window_is_told_everything_the_player_holds() {
        val held = HeldDisplay().apply {
            brightness = 0.3f
            keepOn = true
            orientation = ScreenOrientation.Landscape
        }

        assertEquals(NewWindow(0.3f, true, ScreenOrientation.Landscape), held.forNewWindow())
    }

    @Test
    fun a_new_window_is_told_nothing_when_nothing_is_held() {
        assertEquals(NewWindow(null, false, null), HeldDisplay().forNewWindow())
    }

    @Test
    fun the_apps_own_orientation_is_never_pressed_on_a_new_window() {
        // The camera holds portrait on its own window; "follow the phone" must not undo that.
        val held = HeldDisplay().apply { orientation = ScreenOrientation.Landscape }
        held.orientation = ScreenOrientation.AppDefault

        assertEquals(null, held.forNewWindow().orientation)
    }

    // ---- which window a change goes to ---------------------------------------------------------

    @Test
    fun a_change_goes_to_the_window_in_front() {
        assertEquals("front", windowToChange("front", "last", gone = { false }))
    }

    @Test
    fun a_lock_after_the_pause_still_reaches_the_window_that_was_in_front() {
        // onPause cleared the one in front; the lock in onStop must still put this window back.
        assertEquals("last", windowToChange(null, "last", gone = { false }))
    }

    @Test
    fun a_window_that_is_going_away_is_not_touched() {
        assertEquals(null, windowToChange(null, "last", gone = { true }))
        assertEquals(null, windowToChange<String>(null, null, gone = { false }), "none ever shown")
    }

    @Test
    fun a_portrait_hold_is_told_again_too() {
        val held = HeldDisplay().apply { orientation = ScreenOrientation.Portrait }

        assertEquals(ScreenOrientation.Portrait, held.forNewWindow().orientation)
    }
}
