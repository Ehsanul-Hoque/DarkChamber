package dev.ehsanfahad.darkchamber.app

import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.stop
import dev.ehsanfahad.darkchamber.app.di.appModules
import dev.ehsanfahad.darkchamber.app.root.DefaultLockedComponent
import dev.ehsanfahad.darkchamber.app.root.DefaultRootComponent
import dev.ehsanfahad.darkchamber.app.root.DefaultUnlockedComponent
import dev.ehsanfahad.darkchamber.app.root.RootComponent
import dev.ehsanfahad.darkchamber.app.system.SecretClipboard
import dev.ehsanfahad.darkchamber.app.ui.RootContent
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.logging.initLogging
import dev.ehsanfahad.darkchamber.core.logging.logVaultError
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import org.koin.core.Koin
import org.koin.core.context.startKoin
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.UIVisualEffectView

/**
 * The Swift-facing entry into the shared Compose session gate. `iosApp` hosts the returned
 * `UIViewController` in SwiftUI. The gate's [RootComponent] is built on an Essenty
 * [LifecycleRegistry] whose STOP/RESUME track app background/foreground, so backgrounding locks the
 * vault (the iOS analogue of the Android `onStop` teardown; hard rules #1/#5). The session lives only
 * in memory — nothing is persisted, so there is no saved-state child to restore.
 */
@OptIn(ExperimentalForeignApi::class)
fun MainViewController(): UIViewController {
    val koin = appKoin
    val lifecycle = LifecycleRegistry()

    val root: RootComponent = DefaultRootComponent(
        componentContext = DefaultComponentContext(lifecycle),
        lockedFactory = { ctx, onUnlocked ->
            DefaultLockedComponent(
                componentContext = ctx,
                bootstrap = koin.get(),
                authenticator = koin.get<BiometricAuthenticator>(),
                clipboard = koin.get<SecretClipboard>(),
                mainContext = Dispatchers.Main,
                onUnlocked = onUnlocked,
            )
        },
        unlockedFactory = { ctx, vault, onLock ->
            DefaultUnlockedComponent(
                ctx,
                vault,
                koin,
                onLock = onLock,
                // Nothing, because iOS has neither half of what this is for: no system back reaches
                // the gate (the context above is built without a back handler, so the callback that
                // would offer to leave is never enabled by anything), and an app does not quit
                // itself here — leaving is the home gesture, which backgrounds us and locks the
                // vault through `didEnterBackground`.
                onLeave = {},
            )
        },
        // Wired for symmetry, and for the pickers Phase 1 still has to add. iOS never *needs* the
        // exemption today: `UIDocumentPickerViewController` is presented inside our own window, so
        // the app stays foreground-active and `didEnterBackground` never fires during a pick.
        handoff = koin.get(),
    )

    lifecycle.resume()
    observeAppLifecycle(lifecycle, root)
    observeSnapshotCover()

    return ComposeUIViewController { RootContent(root) }
}

// Start Koin + logging once (no iOS Application to do it, unlike Android's DarkChamberApp). `by lazy`
// guarantees a single startKoin for the process — MainViewController is the only iOS entry point.
private val appKoin: Koin by lazy {
    initLogging()
    startKoin { modules(appModules()) }.koin
}

@OptIn(ExperimentalForeignApi::class)
private fun observeAppLifecycle(lifecycle: LifecycleRegistry, root: RootComponent) {
    val center = NSNotificationCenter.defaultCenter
    center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
        // Lock the session on background (the child lifecycle is standalone, so drive it explicitly),
        // then stop the root lifecycle so Compose pauses.
        root.lockIfUnlocked()
        lifecycle.stop()
    }
    center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
        root.onForeground()
        lifecycle.resume()
    }
}

/** The blur laid over the window while the app is inactive; `null` whenever the app is on screen. */
private var snapshotCover: UIVisualEffectView? = null

/**
 * Obscures the app-switcher snapshot. iOS takes that snapshot after `willResignActive`, so a blur
 * added there is what the system captures instead of vault content.
 *
 * This is **not** screenshot blocking. iOS has no `FLAG_SECURE` equivalent, so a user (or anyone
 * holding the unlocked device) can still capture what is on screen; docs/THREAT_MODEL.md says so and
 * no UI copy may imply otherwise. Whole-window rather than per-screen, for the same reason as
 * Android's flag: a screen can forget to opt in.
 */
@OptIn(ExperimentalForeignApi::class)
private fun observeSnapshotCover() {
    val center = NSNotificationCenter.defaultCenter
    center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
        if (snapshotCover != null) return@addObserverForName
        val window = keyWindow() ?: run {
            // Silent failure would leave vault content in the app-switcher card, so make it visible
            // in a diagnostic log (event name only — no content, hard rule #4).
            logVaultError(SNAPSHOT_COVER_STAGE, VaultError.Unexpected(IllegalStateException("no window")))
            return@addObserverForName
        }
        val cover = UIVisualEffectView(effect = UIBlurEffect.effectWithStyle(UIBlurEffectStyle.UIBlurEffectStyleSystemThickMaterialDark))
        cover.setFrame(window.bounds)
        cover.setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
        window.addSubview(cover)
        snapshotCover = cover
    }
    center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
        snapshotCover?.removeFromSuperview()
        snapshotCover = null
    }
}

// Stage label for the redaction boundary: an event name, never content.
private const val SNAPSHOT_COVER_STAGE = "ios-snapshot-cover"
