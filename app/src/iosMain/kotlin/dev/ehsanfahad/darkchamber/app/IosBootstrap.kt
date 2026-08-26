package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.app.di.appModules
import dev.ehsanfahad.darkchamber.core.model.Outcome
import org.koin.dsl.koinApplication

/**
 * Swift-facing entry points into the `Shared` framework (D1/D7).
 *
 * `makeVaultBootstrap` builds the real iOS composition graph (Documents vault root, Keychain
 * `SecureKeyStore`, native SQLite driver) and returns the [VaultBootstrap]; `runVaultSelfCheck`
 * is the one-shot 5a bring-up probe the iOS host + XCTest call. No vault UI this cycle.
 */

/** Build the real iOS composition graph and return the bootstrap. */
fun makeVaultBootstrap(): VaultBootstrap =
    koinApplication { modules(appModules()) }.koin.get()

/** Cold-open + encrypted round-trip on the simulator/device; `true` iff it succeeds. */
fun runVaultSelfCheck(): Boolean =
    makeVaultBootstrap().selfCheck() is Outcome.Ok
