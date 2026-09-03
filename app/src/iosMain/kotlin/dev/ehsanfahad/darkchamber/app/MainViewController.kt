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
import dev.ehsanfahad.darkchamber.app.ui.RootContent
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.logging.initLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import org.koin.core.Koin
import org.koin.core.context.startKoin
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIViewController

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
                mainContext = Dispatchers.Main,
                onUnlocked = onUnlocked,
            )
        },
        unlockedFactory = { ctx, vault, onLock -> DefaultUnlockedComponent(ctx, vault, koin, onLock) },
    )

    lifecycle.resume()
    observeAppLifecycle(lifecycle, root)

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
        lifecycle.resume()
    }
}
