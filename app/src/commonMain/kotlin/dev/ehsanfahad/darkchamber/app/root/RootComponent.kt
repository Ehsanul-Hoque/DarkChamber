package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.app.system.SystemUiHandoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The **structural session gate** (docs/ARCHITECTURE.md §8). The root has exactly two children —
 * [RootChild.Locked] and [RootChild.Unlocked] — and the unlocked child is *constructed with* a live
 * vault. A decrypted-content screen cannot exist outside the unlocked subtree by construction;
 * locking replaces the whole unlocked subtree (and zeroizes the session).
 *
 * The gate is held in an in-memory [Value], **never** a serializable Decompose config: a
 * [dev.ehsanfahad.darkchamber.core.crypto.VaultSession] holds the raw MasterKey and must never be
 * persisted or survive process death (hard rule #5). So a relaunch always starts [RootChild.Locked].
 */
interface RootComponent {
    val child: Value<RootChild>

    /**
     * Lock now if a session is open (no-op if already locked). Hosts call this when the app goes to
     * the background (iOS `didEnterBackground`, Android `onStop` on a real background — not a config
     * change), so a session never survives backgrounding (hard rules #1/#5). Idempotent.
     *
     * The **one** exemption is a system-UI handoff we launched ourselves — a file picker is a
     * separate activity, so backgrounding is how the user reaches it. See
     * [dev.ehsanfahad.darkchamber.app.system.SystemUiHandoff]; the lock is deferred, never skipped.
     */
    fun lockIfUnlocked()

    /**
     * The app came back to the foreground (Android `onStart`, iOS `willEnterForeground`). Cancels a
     * lock deferred for a system-UI handoff: the user returned, so the picker was not abandoned.
     * Idempotent, and a no-op when nothing was deferred.
     */
    fun onForeground()
}

/** The two — and only two — gate children. */
sealed interface RootChild {
    class Locked(val component: LockedComponent) : RootChild
    class Unlocked(val component: UnlockedComponent) : RootChild
}

/**
 * @param lockedFactory builds the locked child, given a context and the `onUnlocked` hook it calls
 *   once its create/unlock flow produces a live [VaultBootstrap.OpenVault].
 * @param unlockedFactory builds the unlocked child, given a context, the live vault, and the `onLock`
 *   hook it calls to tear down and return to locked.
 */
class DefaultRootComponent(
    componentContext: ComponentContext,
    private val lockedFactory: (ComponentContext, onUnlocked: (VaultBootstrap.OpenVault) -> Unit) -> LockedComponent,
    private val unlockedFactory: (ComponentContext, vault: VaultBootstrap.OpenVault, onLock: () -> Unit) -> UnlockedComponent,
    private val handoff: SystemUiHandoff = SystemUiHandoff(),
    // Injected so host tests can drive the abandoned-handoff watchdog on virtual time.
    mainContext: CoroutineContext = Dispatchers.Main,
    private val handoffGrace: Duration = HANDOFF_GRACE,
) : RootComponent, ComponentContext by componentContext {

    // The active child owns its own lifecycle; a transition destroys the outgoing child — disposing
    // anything scoped to it (the Koin session scope) — before the new one is built. Standalone
    // contexts (not childContext) so the registry is ours to destroy; the session is never persisted
    // (hard rule #5), so there is no saved-state child to restore.
    private var childLifecycle: LifecycleRegistry? = null

    private val _child = MutableValue<RootChild>(RootChild.Locked(createLocked()))
    override val child: Value<RootChild> = _child

    /** Gate-lifetime scope, for the deferred-lock watchdog only. Nothing here touches the session. */
    private val scope = CoroutineScope(mainContext + SupervisorJob())

    /** The armed watchdog for a lock deferred by a system-UI handoff, or null when none is pending. */
    private var pendingLock: Job? = null

    init {
        lifecycle.doOnDestroy { scope.cancel() }
    }

    private fun newChildContext(): ComponentContext {
        childLifecycle?.destroy()
        val lifecycle = LifecycleRegistry().apply { resume() }
        childLifecycle = lifecycle
        return DefaultComponentContext(
            lifecycle = lifecycle,
            // **The host's back handler, passed through explicitly.** Standalone is about the
            // *lifecycle* — this gate destroys its child's registry on lock — and back handling is a
            // separate axis that must not be standalone with it. Omitting it is not neutral:
            // DefaultComponentContext quietly substitutes a private BackDispatcher, so every callback
            // the subtree registers (the item grid leaving selection, the navigation stack popping a
            // screen) waits on a dispatcher nothing will ever call. The press then falls through to
            // the system, which backgrounds the app — and backgrounding locks the vault, so the
            // symptom is "back closes the app and throws away the session" from every screen.
            backHandler = backHandler,
        )
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

    override fun lockIfUnlocked() {
        val unlocked = _child.value as? RootChild.Unlocked ?: return
        if (handoff.inFlight) {
            // The user is in a picker *we* opened; locking here would destroy the screen its result
            // is coming back to. Defer — but arm the watchdog, so a picker that is abandoned rather
            // than finished still locks the vault without anyone having to come back to the app.
            armAbandonedHandoffLock()
            return
        }
        // The unlocked child's lifecycle is standalone (not driven by the app lifecycle), so the host
        // must ask us to lock on background; delegate to its teardown (which returns us to Locked).
        unlocked.component.lock()
    }

    override fun onForeground() {
        // Back on screen: whatever the handoff did, we are no longer a backgrounded live session.
        pendingLock?.cancel()
        pendingLock = null
    }

    private fun armAbandonedHandoffLock() {
        // One watchdog at a time. Overwriting `pendingLock` with a second job would orphan the
        // first — still armed, no longer cancellable by onForeground — and it would lock the vault
        // out from under the user minutes after they came back.
        if (pendingLock?.isActive == true) return
        pendingLock = scope.launch {
            delay(handoffGrace)
            pendingLock = null
            // Still deferred when the grace ran out — the handoff never came back. Lock regardless
            // of `handoff.inFlight`: a picker sitting open for this long is an abandoned phone, and
            // the pick's own cancellation releases the exemption on the way down.
            (_child.value as? RootChild.Unlocked)?.component?.lock()
        }
    }

    private companion object {
        /**
         * How long a self-launched picker may keep the session alive in the background. Long enough
         * to browse a cloud provider, short enough that a phone left on the picker screen does not
         * hold the MasterKey in memory indefinitely. The general on-background auto-lock timeout
         * (docs/ARCHITECTURE.md §8, default 30 s) is a separate, still-unimplemented setting.
         */
        val HANDOFF_GRACE: Duration = 2.minutes
    }
}
