package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIInterfaceOrientationMask
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS
import platform.UIKit.setNeedsUpdateOfSupportedInterfaceOrientations

/**
 * Whether a screen is holding the window in portrait. Read by the Swift host's app delegate, which
 * is where UIKit asks which orientations the window may take: the Compose controller sits inside a
 * SwiftUI hosting controller this code cannot subclass.
 */
fun holdsPortrait(): Boolean = portraitHeld || forced == ScreenOrientation.Portrait

/**
 * Whether the player is holding the window in landscape — either way round, by the device. Read by the
 * same delegate; a screen holding portrait wins.
 */
fun holdsLandscape(): Boolean = !portraitHeld && forced == ScreenOrientation.Landscape

/**
 * What the two answers above are made of. Set only through [holdPortrait] and [forceOrientation], which
 * also ask the window to read them again; tests set them directly, since a test process has no window.
 */
internal var portraitHeld = false
internal var forced = ScreenOrientation.AppDefault

/**
 * Holds the window in [orientation] for the player, whatever the phone's rotation lock says, or lets it
 * follow the device again ([ScreenOrientation.AppDefault]). Main thread only.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun forceOrientation(orientation: ScreenOrientation) {
    forced = orientation
    val root = rootViewController() ?: return
    root.setNeedsUpdateOfSupportedInterfaceOrientations()
    val mask = orientationMaskFor(orientation) ?: return
    keyWindow()?.windowScene?.requestGeometryUpdateWithPreferences(
        UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = mask),
        errorHandler = null,
    )
}

/** The interface orientations an orientation holds the window to; `null` for the app's own. */
internal fun orientationMaskFor(orientation: ScreenOrientation): UIInterfaceOrientationMask? =
    when (orientation) {
        ScreenOrientation.AppDefault -> null
        ScreenOrientation.Portrait -> UIInterfaceOrientationMaskPortrait
        ScreenOrientation.Landscape -> UIInterfaceOrientationMaskLandscape
    }

/** Starts or ends the hold, and asks UIKit to read the delegate's answer again. Main thread only. */
@OptIn(ExperimentalForeignApi::class)
internal fun holdPortrait(held: Boolean) {
    portraitHeld = held
    val root = rootViewController() ?: return
    root.setNeedsUpdateOfSupportedInterfaceOrientations()
    // Re-reading the answer does not by itself turn a phone already held sideways; asking for
    // portrait does. On release the window simply follows the device again.
    if (held) {
        keyWindow()?.windowScene?.requestGeometryUpdateWithPreferences(
            UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = UIInterfaceOrientationMaskPortrait),
            errorHandler = null,
        )
    }
}
