package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation.AppDefault
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation.Landscape
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation.Portrait
import dev.ehsanfahad.darkchamber.feature.vault.presentation.DisplayCommand.Brightness
import dev.ehsanfahad.darkchamber.feature.vault.presentation.DisplayCommand.KeepScreenOn
import dev.ehsanfahad.darkchamber.feature.vault.presentation.DisplayCommand.Orientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** When the viewer changes the screen, and when it puts it back. */
class DisplayRulesTest {
    private fun onVideo() = DisplayRules().also { it.pageShown(video = true) }

    // ---- orientation -----------------------------------------------------------------------------

    @Test
    fun opening_a_video_changes_nothing_until_the_button_is_pressed() {
        assertTrue(DisplayRules().pageShown(video = true).isEmpty())
    }

    @Test
    fun the_button_turns_to_landscape_then_back_to_portrait_and_names_where_it_goes() {
        val rules = onVideo()
        assertEquals(Landscape, rules.buttonTarget)

        assertEquals(listOf(Orientation(Landscape)), rules.orientationPressed())
        assertEquals(Portrait, rules.buttonTarget)

        assertEquals(listOf(Orientation(Portrait)), rules.orientationPressed())
        assertEquals(Landscape, rules.buttonTarget)
    }

    @Test
    fun an_image_or_audio_page_follows_the_apps_own_orientation() {
        val rules = onVideo()
        rules.orientationPressed()

        assertEquals(listOf(Orientation(AppDefault)), rules.pageShown(video = false))
    }

    @Test
    fun swiping_back_to_a_video_applies_the_choice_again() {
        val rules = onVideo()
        rules.orientationPressed()
        rules.pageShown(video = false)

        assertEquals(listOf(Orientation(Landscape)), rules.pageShown(video = true))
    }

    @Test
    fun one_video_to_the_next_keeps_the_choice_without_asking_again() {
        val rules = onVideo()
        rules.orientationPressed()

        assertTrue(rules.pageShown(video = true).isEmpty())
    }

    @Test
    fun a_portrait_choice_also_holds_on_video_pages_only() {
        val rules = onVideo()
        rules.orientationPressed()
        rules.orientationPressed() // portrait, chosen

        assertEquals(listOf(Orientation(AppDefault)), rules.pageShown(video = false))
        assertEquals(listOf(Orientation(Portrait)), rules.pageShown(video = true))
    }

    @Test
    fun the_button_does_nothing_off_a_video_page() {
        val rules = DisplayRules()
        rules.pageShown(video = false)

        assertTrue(rules.orientationPressed().isEmpty())
        assertTrue(rules.pageShown(video = true).isEmpty(), "and nothing was chosen")
    }

    // ---- brightness ------------------------------------------------------------------------------

    @Test
    fun the_slide_sets_the_brightness_on_a_video_page() {
        val rules = onVideo()

        assertEquals(listOf(Brightness(0.3f)), rules.brightnessSlid(0.3f))
        assertEquals(listOf(Brightness(0.6f)), rules.brightnessSlid(0.6f))
    }

    @Test
    fun the_slide_does_nothing_off_a_video_page() {
        val rules = DisplayRules()
        rules.pageShown(video = false)

        assertTrue(rules.brightnessSlid(0.3f).isEmpty())
    }

    @Test
    fun leaving_the_video_page_hands_the_brightness_back() {
        val rules = onVideo()
        rules.brightnessSlid(0.3f)

        assertEquals(listOf(Brightness(null)), rules.pageShown(video = false))
    }

    @Test
    fun leaving_from_one_video_to_another_hands_it_back_too() {
        val rules = onVideo()
        rules.brightnessSlid(0.3f)

        assertEquals(listOf(Brightness(null)), rules.pageShown(video = true))
    }

    @Test
    fun brightness_never_touched_is_never_handed_back() {
        val rules = onVideo()

        assertTrue(rules.pageShown(video = false).isEmpty())
    }

    @Test
    fun coming_back_does_not_bring_the_brightness_back() {
        val rules = onVideo()
        rules.brightnessSlid(0.3f)
        rules.pageShown(video = false)

        assertTrue(rules.pageShown(video = true).isEmpty())
    }

    @Test
    fun leaving_after_both_hands_back_the_brightness_then_the_orientation() {
        val rules = onVideo()
        rules.orientationPressed()
        rules.brightnessSlid(0.3f)

        assertEquals(listOf(Brightness(null), Orientation(AppDefault)), rules.pageShown(video = false))
    }

    // ---- closing ---------------------------------------------------------------------------------

    @Test
    fun closing_puts_back_whatever_was_changed() {
        val rules = onVideo()
        rules.orientationPressed()
        rules.brightnessSlid(0.3f)

        assertEquals(listOf(Brightness(null), Orientation(AppDefault)), rules.closed())
    }

    @Test
    fun closing_with_nothing_changed_sends_nothing() {
        assertTrue(onVideo().closed().isEmpty())
    }

    @Test
    fun closing_from_an_image_page_after_a_choice_has_nothing_left_to_put_back() {
        val rules = onVideo()
        rules.orientationPressed()
        rules.pageShown(video = false) // already back to the app's own

        assertTrue(rules.closed().isEmpty())
    }

    @Test
    fun once_closed_nothing_more_is_sent() {
        val rules = onVideo()
        rules.orientationPressed()
        rules.closed()

        assertTrue(rules.closed().isEmpty())
        assertTrue(rules.pageShown(video = true).isEmpty())
        assertTrue(rules.orientationPressed().isEmpty())
        assertTrue(rules.brightnessSlid(0.5f).isEmpty())
        assertTrue(rules.playbackChanged(playing = true).isEmpty())
    }

    // ---- keeping the screen on -------------------------------------------------------------------

    @Test
    fun the_screen_stays_on_while_something_plays_and_is_let_sleep_when_it_stops() {
        val rules = DisplayRules().also { it.pageShown(video = false) } // audio plays too

        assertEquals(listOf(KeepScreenOn(true)), rules.playbackChanged(playing = true))
        assertEquals(listOf(KeepScreenOn(false)), rules.playbackChanged(playing = false))
    }

    @Test
    fun only_a_change_is_sent() {
        val rules = DisplayRules()

        assertTrue(rules.playbackChanged(playing = false).isEmpty(), "the screen was never held")
        rules.playbackChanged(playing = true)
        assertTrue(rules.playbackChanged(playing = true).isEmpty())
    }

    @Test
    fun closing_while_playing_lets_the_screen_sleep_after_putting_the_rest_back() {
        val rules = onVideo()
        rules.orientationPressed()
        rules.playbackChanged(playing = true)

        assertEquals(
            listOf(Orientation(AppDefault), KeepScreenOn(false)),
            rules.closed(),
        )
    }

    @Test
    fun closing_while_paused_does_not_touch_the_screen() {
        val rules = onVideo()
        rules.playbackChanged(playing = true)
        rules.playbackChanged(playing = false)

        assertTrue(rules.closed().isEmpty())
    }
}
