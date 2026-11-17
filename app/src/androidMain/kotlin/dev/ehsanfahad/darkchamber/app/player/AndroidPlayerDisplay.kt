package dev.ehsanfahad.darkchamber.app.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.WindowManager
import dev.ehsanfahad.darkchamber.app.auth.ActivityHolder
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerDisplay
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation

/**
 * The display controls on Android, on the activity in front: this window's brightness (the rest of the
 * phone is untouched, and the window forgets it when it goes) and the activity's requested orientation,
 * which holds whatever auto-rotate is set to. With no activity in front there is nothing to change now.
 *
 * What it holds is remembered ([HeldDisplay]) and told to every window that comes to the front after:
 * the orientation button itself recreates the activity, and the new window would otherwise start at
 * the system's brightness and let the screen sleep mid-video.
 */
internal class AndroidPlayerDisplay(private val activities: ActivityHolder) : PlayerDisplay {
    private val held = HeldDisplay()

    init {
        activities.whenShown { activity ->
            val told = held.forNewWindow()
            told.brightness?.let { applyBrightness(activity, it) }
            if (told.keepOn) applyKeepOn(activity, true)
            told.orientation?.let { activity.requestedOrientation = DisplayMapping.requestedOrientation(it) }
        }
    }

    override fun brightness(): Float {
        val activity = activities.activity ?: return DisplayMapping.UNKNOWN_BRIGHTNESS
        val set = activity.window.attributes.screenBrightness
        if (set >= 0f) return set
        // Following the system: start the slide from the system's own level, as near as it says.
        return runCatching {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / SYSTEM_MAX
        }.getOrDefault(DisplayMapping.UNKNOWN_BRIGHTNESS).coerceIn(0f, 1f)
    }

    override fun setBrightness(level: Float?) {
        held.brightness = level
        activities.activity?.let { applyBrightness(it, level) }
    }

    override fun setOrientation(orientation: ScreenOrientation) {
        held.orientation = orientation
        activities.activity?.requestedOrientation = DisplayMapping.requestedOrientation(orientation)
    }

    // A window flag, so it goes with the window: nothing is left holding the screen after the app.
    override fun keepScreenOn(on: Boolean) {
        held.keepOn = on
        activities.activity?.let { applyKeepOn(it, on) }
    }

    private fun applyBrightness(activity: Activity, level: Float?) {
        val window = activity.window
        window.attributes = window.attributes.apply { screenBrightness = DisplayMapping.windowBrightness(level) }
    }

    private fun applyKeepOn(activity: Activity, on: Boolean) {
        if (on) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private companion object {
        const val SYSTEM_MAX = 255f
    }
}

/** The Android values behind the display controls. */
internal object DisplayMapping {
    /** Where the slide starts when the system will not say. */
    const val UNKNOWN_BRIGHTNESS = 0.5f

    /** The dimmest the slide goes: at 0 some screens switch their backlight off. */
    const val DIMMEST = 0.02f

    /** A window brightness: [level] held above [DIMMEST], or the system's own when `null`. */
    fun windowBrightness(level: Float?): Float =
        level?.coerceIn(DIMMEST, 1f) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    /** The requested orientation: the app's own, portrait, or either landscape by the sensor. */
    fun requestedOrientation(orientation: ScreenOrientation): Int =
        when (orientation) {
            ScreenOrientation.AppDefault -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ScreenOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ScreenOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
}
