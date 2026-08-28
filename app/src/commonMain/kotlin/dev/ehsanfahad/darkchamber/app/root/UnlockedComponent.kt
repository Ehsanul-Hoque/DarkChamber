package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.ComponentContext
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession

/**
 * The unlocked child — **constructed with a live vault**, which is the structural gate invariant: a
 * decrypted-content screen cannot exist without a [VaultSession]. This cycle it exposes the [session]
 * and a manual [lock]; the real vault UI is Phase 1 and the full lock teardown (purge `tmp/`, close
 * the catalog driver, dispose the Koin session scope, Essenty `onStop`) lands in TASK-008.
 */
interface UnlockedComponent {
    val session: VaultSession
    /** Lock now: tear down and return the gate to locked. */
    fun lock()
}

class DefaultUnlockedComponent(
    componentContext: ComponentContext,
    private val vault: VaultBootstrap.OpenVault,
    private val onLock: () -> Unit,
) : UnlockedComponent, ComponentContext by componentContext {

    override val session: VaultSession get() = vault.session

    override fun lock() {
        // Minimal teardown this cycle: zeroize the MK (hard rule #5). TASK-008 adds purgeTmp() +
        // catalog close + session-scope disposal before signalling the gate.
        vault.session.close()
        onLock()
    }
}
