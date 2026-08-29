package dev.ehsanfahad.darkchamber

import android.os.Bundle
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        root = retainedComponent { componentContext -> buildRoot(componentContext) }
        setContent { RootContent(root) }
    }

    override fun onStop() {
        // Lock on a real background — but NOT on a configuration change (rotation), where the
        // retained root deliberately keeps the session across the Activity's recreation.
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
                    mainContext = Dispatchers.Main,
                    onUnlocked = onUnlocked,
                )
            },
            unlockedFactory = { ctx, vault, onLock ->
                DefaultUnlockedComponent(ctx, vault, koin, onLock)
            },
        )
}
