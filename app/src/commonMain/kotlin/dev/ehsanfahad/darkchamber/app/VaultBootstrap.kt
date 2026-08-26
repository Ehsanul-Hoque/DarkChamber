package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.app.di.CatalogProvider
import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.crypto.SecureKeyStore

/**
 * The fail-closed cold-open of the vault — the composition root's single entry point.
 *
 * Resolves the app-private vault root, opens the catalog + storage, and creates-or-unlocks a
 * [dev.ehsanfahad.darkchamber.core.crypto.VaultSession] (by passphrase this cycle — the biometric
 * / hardware-slot path is 5b). Every step is `Outcome`-typed; the first failure aborts the open and
 * is surfaced, never a half-initialized vault (D6).
 *
 * Constructed by Koin ([dev.ehsanfahad.darkchamber.app.di.commonAppModule]). The
 * `openOrCreateVault` / `selfCheck` behaviour lands in TASK-005; this cycle wires the dependency
 * graph so the composition root resolves.
 *
 * @param rootResolver resolves + prepares the backup-excluded vault root.
 * @param engine the sole cryptographic primitive seam (hard rule #2).
 * @param catalogProvider opens the catalog over a resolved root (platform-bound driver creation).
 * @param secureKeyStore the HARDWARE-slot wrap seam (used by the biometric path in 5b).
 */
class VaultBootstrap(
    private val rootResolver: VaultRootResolver,
    private val engine: CryptoEngine,
    private val catalogProvider: CatalogProvider,
    private val secureKeyStore: SecureKeyStore,
)
