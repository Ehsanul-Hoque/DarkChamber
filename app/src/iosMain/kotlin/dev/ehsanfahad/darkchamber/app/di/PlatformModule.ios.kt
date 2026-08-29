package dev.ehsanfahad.darkchamber.app.di

import dev.ehsanfahad.darkchamber.app.IosVaultRootResolver
import dev.ehsanfahad.darkchamber.app.VaultRootResolver
import dev.ehsanfahad.darkchamber.app.auth.IosBiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.crypto.IosSecureKeyStore
import dev.ehsanfahad.darkchamber.core.crypto.SecureKeyStore
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.db.DriverFactory
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS composition root (D2). Binds the platform-resolved vault root (from `NSDocumentDirectory`,
 * backup-excluded), the Keychain-backed `SecureKeyStore`, and the catalog opener (real
 * `NativeSqliteDriver` under `<root>/db/catalog.db`).
 */
actual fun platformModule(): Module = module {
    single<VaultRootResolver> { IosVaultRootResolver() }
    single<SecureKeyStore> { IosSecureKeyStore() }
    single<BiometricAuthenticator> { IosBiometricAuthenticator() }
    single<CatalogProvider> { CatalogProvider { root -> openCatalog(root) } }
}

private fun openCatalog(vaultRoot: Path): Outcome<CatalogStore> =
    try {
        val dbPath = Path(Path(vaultRoot, "db"), "catalog.db").toString()
        CatalogStore.create(DriverFactory(dbPath).create())
    } catch (e: Exception) {
        Outcome.Err(VaultError.Db(e))
    }
