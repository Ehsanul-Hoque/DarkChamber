package dev.ehsanfahad.darkchamber.app.media

import dev.ehsanfahad.darkchamber.app.forced
import dev.ehsanfahad.darkchamber.app.holdsLandscape
import dev.ehsanfahad.darkchamber.app.holdsPortrait
import dev.ehsanfahad.darkchamber.app.orientationMaskFor
import dev.ehsanfahad.darkchamber.app.portraitHeld
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS display controls: the orientation the delegate is told to hold, and the phone's brightness.
 * The holds are set directly: asking the window to turn needs an app, and a test process has none (it
 * crashes reaching `UIApplication`) — that part is for a device.
 */
class IosPlayerDisplayTest {
    @AfterTest
    fun putBack() {
        forced = ScreenOrientation.AppDefault
        portraitHeld = false
    }

    // ---- orientation -----------------------------------------------------------------------------

    @Test
    fun each_orientation_holds_the_window_to_its_own_sides_and_the_apps_own_holds_nothing() {
        assertNull(orientationMaskFor(ScreenOrientation.AppDefault))
        assertEquals(UIInterfaceOrientationMaskPortrait, orientationMaskFor(ScreenOrientation.Portrait))
        assertEquals(UIInterfaceOrientationMaskLandscape, orientationMaskFor(ScreenOrientation.Landscape))
    }

    @Test
    fun the_delegate_is_told_landscape_while_the_player_holds_it() {
        forced = ScreenOrientation.Landscape

        assertTrue(holdsLandscape())
        assertFalse(holdsPortrait())
    }

    @Test
    fun the_delegate_is_told_portrait_while_the_player_holds_it() {
        forced = ScreenOrientation.Portrait

        assertTrue(holdsPortrait())
        assertFalse(holdsLandscape())
    }

    @Test
    fun the_apps_own_orientation_holds_neither() {
        forced = ScreenOrientation.Landscape
        forced = ScreenOrientation.AppDefault

        assertFalse(holdsPortrait())
        assertFalse(holdsLandscape())
    }

    @Test
    fun a_screen_holding_portrait_wins_over_the_players_landscape() {
        forced = ScreenOrientation.Landscape
        portraitHeld = true

        assertTrue(holdsPortrait())
        assertFalse(holdsLandscape())
    }

    // ---- brightness ------------------------------------------------------------------------------

    private class FakeScreen(override var level: Double) : ScreenBrightness

    @Test
    fun the_brightness_read_is_the_screens() {
        assertEquals(0.7f, IosPlayerDisplay(FakeScreen(0.7)).brightness())
    }

    @Test
    fun the_slide_sets_the_phones_brightness() {
        val screen = FakeScreen(0.7)
        val display = IosPlayerDisplay(screen)

        display.setBrightness(0.3f)
        assertEquals(0.3, screen.level, 0.0001)
        display.setBrightness(0.5f)
        assertEquals(0.5, screen.level, 0.0001)
    }

    @Test
    fun handing_it_back_restores_the_level_found_before_the_first_change() {
        val screen = FakeScreen(0.7)
        val display = IosPlayerDisplay(screen)
        display.setBrightness(0.3f)
        display.setBrightness(0.5f)

        display.setBrightness(null)

        assertEquals(0.7, screen.level, 0.0001)
    }

    @Test
    fun handing_back_what_was_never_changed_touches_nothing() {
        val screen = FakeScreen(0.7)
        val display = IosPlayerDisplay(screen)
        screen.level = 0.4 // the person changed it in Control Centre

        display.setBrightness(null)

        assertEquals(0.4, screen.level, 0.0001)
    }

    @Test
    fun after_handing_back_a_new_change_keeps_the_level_found_then() {
        val screen = FakeScreen(0.7)
        val display = IosPlayerDisplay(screen)
        display.setBrightness(0.3f)
        display.setBrightness(null)
        screen.level = 0.6

        display.setBrightness(0.2f)
        display.setBrightness(null)

        assertEquals(0.6, screen.level, 0.0001)
    }

    @Test
    fun a_level_outside_nothing_to_full_is_held_to_it() {
        val screen = FakeScreen(0.7)
        val display = IosPlayerDisplay(screen)

        display.setBrightness(1.4f)
        assertEquals(1.0, screen.level, 0.0001)
        display.setBrightness(-0.2f)
        assertEquals(0.0, screen.level, 0.0001)
    }

    // ---- keeping the screen on -------------------------------------------------------------------

    private class FakeIdleTimer(override var disabled: Boolean = false) : IdleTimer

    @Test
    fun keeping_the_screen_on_turns_the_sleep_timer_off_and_back_on() {
        val idle = FakeIdleTimer()
        val display = IosPlayerDisplay(FakeScreen(0.5), idle)

        display.keepScreenOn(true)
        assertTrue(idle.disabled)
        display.keepScreenOn(false)
        assertFalse(idle.disabled)
    }
}
