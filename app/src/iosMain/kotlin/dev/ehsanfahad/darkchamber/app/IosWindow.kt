package dev.ehsanfahad.darkchamber.app

import platform.UIKit.UIApplication
import platform.UIKit.UIScene
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * The window this app is actually showing.
 *
 * `UIApplication.keyWindow` is deprecated and unreliable in a scene-based host, which `iosApp` is —
 * its SwiftUI `App`/`WindowGroup` puts every window under a `UIWindowScene`. It can return `nil`
 * there, and both callers treat `nil` as "do nothing": the snapshot blur would silently not be
 * applied, leaving vault content in the app-switcher card. So resolve through the scene graph first
 * and keep the old accessor only as a last resort.
 *
 * Foreground-active scenes are preferred, because the window worth covering is the one on screen.
 */
internal fun keyWindow(): UIWindow? {
    val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
    val ordered = scenes.sortedBy { scene: UIScene ->
        if (scene.activationState == UISceneActivationStateForegroundActive) 0 else 1
    }
    val windows = ordered.flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }

    return windows.firstOrNull { it.isKeyWindow() }
        ?: windows.firstOrNull()
        ?: UIApplication.sharedApplication.keyWindow
}

/** The app's root view controller — the single Compose host everything is presented from. */
internal fun rootViewController(): UIViewController? = keyWindow()?.rootViewController
