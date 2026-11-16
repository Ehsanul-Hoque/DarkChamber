package dev.ehsanfahad.darkchamber.app.player

import android.content.pm.ActivityInfo
import android.view.WindowManager
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Android values behind the display controls, without a device. */
class DisplayMappingTest {
    @Test
    fun the_apps_own_orientation_is_the_manifests_portrait_holds_and_landscape_follows_the_sensor() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, DisplayMapping.requestedOrientation(ScreenOrientation.AppDefault))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, DisplayMapping.requestedOrientation(ScreenOrientation.Portrait))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, DisplayMapping.requestedOrientation(ScreenOrientation.Landscape))
    }

    @Test
    fun a_level_is_the_windows_brightness() {
        assertEquals(0.4f, DisplayMapping.windowBrightness(0.4f))
        assertEquals(1f, DisplayMapping.windowBrightness(1f))
    }

    @Test
    fun the_dimmest_is_never_dark_enough_to_switch_the_backlight_off() {
        assertEquals(DisplayMapping.DIMMEST, DisplayMapping.windowBrightness(0f))
        assertEquals(DisplayMapping.DIMMEST, DisplayMapping.windowBrightness(0.01f))
    }

    @Test
    fun handing_it_back_is_the_windows_follow_the_system_value() {
        assertEquals(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, DisplayMapping.windowBrightness(null))
    }

    @Test
    fun a_level_past_full_is_full() {
        assertEquals(1f, DisplayMapping.windowBrightness(1.3f))
    }
}
