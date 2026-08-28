package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.app.VaultBootstrap

/**
 * The **structural session gate** (docs/ARCHITECTURE.md §8). The root has exactly two children —
 * [RootChild.Locked] and [RootChild.Unlocked] — and the unlocked child is *constructed with* a live
 * vault. A decrypted-content screen cannot exist outside the unlocked subtree by construction;
 * locking replaces the whole unlocked subtree (and, TASK-008, zeroizes the session).
 *
 * The gate is held in an in-memory [Value], **never** a serializable Decompose config: a
 * [dev.ehsanfahad.darkchamber.core.crypto.VaultSession] holds the raw MasterKey and must never be
 * persisted or survive process death (hard rule #5). So a relaunch always starts [RootChild.Locked].
 */
interface RootComponent {
    val child: Value<RootChild>
}

/** The two — and only two — gate children. */
sealed interface RootChild {
    class Locked(val component: LockedComponent) : RootChild
    class Unlocked(val component: UnlockedComponent) : RootChild
}

/**
 * @param lockedFactory builds the locked child, given a context and the `onUnlocked` hook it calls
 *   once its create/unlock flow (TASK-010) produces a live [VaultBootstrap.OpenVault].
 * @param unlockedFactory builds the unlocked child, given a context, the live vault, and the `onLock`
 *   hook it calls to tear down and return to locked.
 */
class DefaultRootComponent(
    componentContext: ComponentContext,
    private val lockedFactory: (ComponentContext, onUnlocked: (VaultBootstrap.OpenVault) -> Unit) -> LockedComponent,
    private val unlockedFactory: (ComponentContext, vault: VaultBootstrap.OpenVault, onLock: () -> Unit) -> UnlockedComponent,
) : RootComponent, ComponentContext by componentContext {

    // The active child owns its own lifecycle; a transition destroys the outgoing child — disposing
    // anything scoped to it (TASK-008's Koin session scope) — before the new one is built. Standalone
    // contexts (not childContext) so the registry is ours to destroy; the session is never persisted
    // (hard rule #5), so there is no saved-state child to restore.
    private var childLifecycle: LifecycleRegistry? = null

    private val _child = MutableValue<RootChild>(RootChild.Locked(createLocked()))
    override val child: Value<RootChild> = _child

    private fun newChildContext(): ComponentContext {
        childLifecycle?.destroy()
        val lifecycle = LifecycleRegistry().apply { resume() }
        childLifecycle = lifecycle
        return DefaultComponentContext(lifecycle)
    }

    private fun createLocked(): LockedComponent =
        lockedFactory(newChildContext()) { vault -> toUnlocked(vault) }

    private fun toUnlocked(vault: VaultBootstrap.OpenVault) {
        _child.value = RootChild.Unlocked(
            unlockedFactory(newChildContext(), vault) { toLocked() },
        )
    }

    private fun toLocked() {
        // Replace the entire unlocked subtree with a fresh locked child (the subtree — and any
        // decrypted-content state it held — ceases to exist).
        _child.value = RootChild.Locked(createLocked())
    }
}
