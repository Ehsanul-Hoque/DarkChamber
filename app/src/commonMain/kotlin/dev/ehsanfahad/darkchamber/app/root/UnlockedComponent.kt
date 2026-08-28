package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnStop
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import kotlin.random.Random
import kotlin.random.nextULong

/**
 * The unlocked child — **constructed with a live vault**, which is the structural gate invariant: a
 * decrypted-content screen cannot exist without a [VaultSession]. This cycle it exposes the [session]
 * and a manual [lock]; the real vault UI is Phase 1.
 *
 * It owns a **Koin session scope** holding the vault's session/storage/catalog (feature UI resolves
 * session-scoped deps from it in Phase 1). Locking — manually **or** on background (Essenty
 * `onStop`) — runs the teardown once: zeroize the MK, purge decrypted `tmp/` scratch, close the
 * catalog driver, dispose the scope, then hand control back to the gate (→ Locked). A session that
 * survives backgrounding is theater (hard rules #1/#5; closes the 5a review-#3 driver leak).
 */
interface UnlockedComponent {
    val session: VaultSession
    /** Lock now: tear down and return the gate to locked. */
    fun lock()
}

class DefaultUnlockedComponent(
    componentContext: ComponentContext,
    private val vault: VaultBootstrap.OpenVault,
    koin: Koin,
    private val onLock: () -> Unit,
) : UnlockedComponent, ComponentContext by componentContext {

    private val scope: Scope =
        koin.createScope("vault-session-${Random.nextULong()}", named(SESSION_SCOPE)).apply {
            declare(vault.session)
            declare(vault.storage)
            declare(vault.catalog)
        }

    private var tornDown = false

    init {
        // Auto-lock on background. A manual lock() sets tornDown before the ensuing destroy re-fires
        // onStop, so the guard stops a re-entrant second lock; onDestroy is the last-resort net.
        lifecycle.doOnStop { if (!tornDown) lock() }
        lifecycle.doOnDestroy { tearDown() }
    }

    override val session: VaultSession get() = vault.session

    override fun lock() {
        if (tornDown) return // already locking/locked — signal the gate exactly once
        tearDown()
        onLock()
    }

    private fun tearDown() {
        if (tornDown) return
        tornDown = true
        vault.session.close() // zeroize MK (hard rule #5)
        vault.storage.purgeTmp() // wipe decrypted scratch (hard rule #1)
        vault.catalog.close() // close the SqlDriver (resolves 5a review #3)
        scope.close() // dispose the session scope
    }

    private companion object {
        const val SESSION_SCOPE = "VaultSession"
    }
}
