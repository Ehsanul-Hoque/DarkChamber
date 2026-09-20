package dev.ehsanfahad.darkchamber

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.retainedComponent
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.app.auth.ActivityHolder
import dev.ehsanfahad.darkchamber.app.root.DefaultLockedComponent
import dev.ehsanfahad.darkchamber.app.root.DefaultRootComponent
import dev.ehsanfahad.darkchamber.app.root.DefaultUnlockedComponent
import dev.ehsanfahad.darkchamber.app.root.RootComponent
import dev.ehsanfahad.darkchamber.app.system.SecretClipboard
import dev.ehsanfahad.darkchamber.app.ui.RootContent
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import kotlinx.coroutines.Dispatchers
import org.koin.core.context.GlobalContext

/**
 * The Android host of the Decompose session gate. A **`FragmentActivity`** because
 * `BiometricPrompt` requires one; it publishes itself to the [ActivityHolder] while resumed so the
 * biometric authenticator can present over it, and builds the [RootComponent] as a
 * `retainedComponent` so the gate (and any unlocked session) survives configuration changes without
 * being persisted (hard rule #5 — the session never touches saved state).
 */
class MainActivity : FragmentActivity() {

    private val koin get() = GlobalContext.get()
    private lateinit var root: RootComponent

    override fun onCreate(savedInstanceState: Bundle?) {
        // Both bars told explicitly that they sit on a dark background, so their icons are drawn
        // light. The default `enableEdgeToEdge()` uses SystemBarStyle.auto, which picks that from
        // the *device's* dark-mode setting rather than from what this app actually draws — and this
        // app is dark whatever the device is set to. On a phone in light mode auto chose dark icons
        // for a light background it assumed was there, leaving them invisible against ours.
        //
        // Pinned to dark rather than derived from the theme because there is only one theme to
        // derive from today. When the light theme becomes reachable this has to follow it instead,
        // or the same mismatch returns the other way up.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Unconditionally, for the whole app: screenshots and screen recording are blocked and the
        // app-switcher thumbnail is blank. Whole-app rather than per-screen on purpose — every
        // screen in the unlocked tree shows vault content, the locked tree shows the recovery key,
        // and a defence a new screen can forget to opt into is not a defence.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)

        root = retainedComponent { componentContext -> buildRoot(componentContext) }
        setContent { RootContent(root) }
    }

    override fun onStart() {
        super.onStart()
        // Back on screen. Cancels a lock the gate deferred for a picker we launched — the user came
        // back, so the handoff was not abandoned.
        root.onForeground()
    }

    override fun onStop() {
        // Lock on a real background — but NOT on a configuration change (rotation), where the
        // retained root deliberately keeps the session across the Activity's recreation. The gate
        // itself defers (never skips) the lock while a picker we launched is still in flight; SAF
        // is a separate activity, so this callback is exactly what firing it looks like.
        if (!isChangingConfigurations) root.lockIfUnlocked()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        koin.get<ActivityHolder>().activity = this
    }

    override fun onPause() {
        // Only clear if it's still us — a newer Activity may already own the prompt host.
        val holder = koin.get<ActivityHolder>()
        if (holder.activity === this) holder.activity = null
        super.onPause()
    }

    private fun buildRoot(componentContext: ComponentContext): RootComponent =
        DefaultRootComponent(
            componentContext = componentContext,
            lockedFactory = { ctx, onUnlocked ->
                DefaultLockedComponent(
                    componentContext = ctx,
                    bootstrap = koin.get<VaultBootstrap>(),
                    authenticator = koin.get<BiometricAuthenticator>(),
                    clipboard = koin.get<SecretClipboard>(),
                    mainContext = Dispatchers.Main,
                    onUnlocked = onUnlocked,
                )
            },
            unlockedFactory = { ctx, vault, onLock ->
                DefaultUnlockedComponent(ctx, vault, koin, onLock = onLock)
            },
            // The same instance the file picker marks its round-trip on.
            handoff = koin.get(),
        )
}
