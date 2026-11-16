package dev.ehsanfahad.darkchamber.app.media

import dev.ehsanfahad.darkchamber.app.forceOrientation
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerDisplay
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation
import platform.UIKit.UIScreen

/**
 * The display controls on iOS. **Brightness here is the whole phone's**, not this app's: the level found
 * before the first change is kept, and handing it back puts exactly that back. Orientation goes through
 * the app delegate's answer ([forceOrientation]). Volume is the player's own (see [IosVaultPlayer]).
 *
 * [screen] is the screen's brightness, read and set — `UIScreen` in the app, a stand-in in tests.
 */
internal class IosPlayerDisplay(
    private val screen: ScreenBrightness = MainScreenBrightness,
) : PlayerDisplay {
    /** The phone's brightness before the first change, while the player holds it; `null` otherwise. */
    private var before: Double? = null

    override fun brightness(): Float = screen.level.toFloat()

    override fun setBrightness(level: Float?) {
        if (level == null) {
            before?.let { screen.level = it }
            before = null
            return
        }
        if (before == null) before = screen.level
        screen.level = level.coerceIn(0f, 1f).toDouble()
    }

    override fun setOrientation(orientation: ScreenOrientation) = forceOrientation(orientation)
}

/** The screen's brightness, from 0 to 1. */
internal interface ScreenBrightness {
    var level: Double
}

private object MainScreenBrightness : ScreenBrightness {
    override var level: Double
        get() = UIScreen.mainScreen.brightness
        set(value) {
            UIScreen.mainScreen.brightness = value
        }
}
