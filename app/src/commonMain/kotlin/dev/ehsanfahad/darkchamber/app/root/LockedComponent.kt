package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.ComponentContext
import dev.ehsanfahad.darkchamber.app.VaultBootstrap

/** Whether the locked screen offers first-run **create** or returning-user **unlock**. */
enum class LockMode { Create, Unlock }

/**
 * The locked child: the create-or-unlock surface. This cycle it carries only the [mode] decision
 * (from whether a keystore exists); the create/unlock/biometric intents + their state land with the
 * Compose UI (TASK-010), and the platform biometric authenticator + session scope with TASK-008/009.
 *
 * When a flow produces a live vault it is handed up the gate via the `onUnlocked` hook the root wired
 * in (see [DefaultLockedComponent.completeUnlock]).
 */
interface LockedComponent {
    val mode: LockMode
}

class DefaultLockedComponent(
    componentContext: ComponentContext,
    override val mode: LockMode,
    private val onUnlocked: (VaultBootstrap.OpenVault) -> Unit,
) : LockedComponent, ComponentContext by componentContext {

    /**
     * Hand a freshly created/unlocked [vault] up to the gate (→ the unlocked subtree). Called by the
     * create/unlock/biometric intents once they land an [VaultBootstrap.OpenVault] (TASK-010).
     */
    fun completeUnlock(vault: VaultBootstrap.OpenVault) {
        onUnlocked(vault)
    }
}
