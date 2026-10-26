package dev.ehsanfahad.darkchamber.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS
import platform.UIKit.setNeedsUpdateOfSupportedInterfaceOrientations

/**
 * Whether a screen is holding the window in portrait. Read by the Swift host's app delegate, which
 * is where UIKit asks which orientations the window may take: the Compose controller sits inside a
 * SwiftUI hosting controller this code cannot subclass.
 */
fun holdsPortrait(): Boolean = portraitHeld

private var portraitHeld = false

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
