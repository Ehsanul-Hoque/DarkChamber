package dev.ehsanfahad.darkchamber.app.di

import android.content.Context
import dev.ehsanfahad.darkchamber.app.AndroidVaultRootResolver
import dev.ehsanfahad.darkchamber.app.VaultRootResolver
import dev.ehsanfahad.darkchamber.app.auth.ActivityHolder
import dev.ehsanfahad.darkchamber.app.auth.AndroidBiometricAuthenticator
import dev.ehsanfahad.darkchamber.app.auth.DefaultActivityHolder
import dev.ehsanfahad.darkchamber.app.picker.AndroidFilePicker
import dev.ehsanfahad.darkchamber.app.picker.AndroidPhotoPicker
import dev.ehsanfahad.darkchamber.core.crypto.AndroidSecureKeyStore
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.crypto.SecureKeyStore
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.db.DriverFactory
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import kotlinx.io.files.Path
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android composition root. Binds the platform-resolved vault root (from `Context.filesDir`,
 * threaded via `androidContext()`), the Keystore-backed `SecureKeyStore`, and the catalog opener
 * (real `AndroidSqliteDriver` under `<root>/db/catalog.db`).
 */
actual fun platformModule(): Module = module {
    single<VaultRootResolver> { AndroidVaultRootResolver(androidContext()) }
    single<SecureKeyStore> { AndroidSecureKeyStore() }
    // The current FragmentActivity for the biometric prompt — set by MainActivity.
    single<ActivityHolder> { DefaultActivityHolder() }
    single<BiometricAuthenticator> { AndroidBiometricAuthenticator { get<ActivityHolder>().activity } }
    // The vault feature's picker seams, reaching the current activity the same way. Both take the
    // one shared SystemUiHandoff: each is a separate activity, so each stops ours while it is up.
    single<FilePicker> { AndroidFilePicker({ get<ActivityHolder>().activity }, get()) }
    single<PhotoPicker> { AndroidPhotoPicker({ get<ActivityHolder>().activity }, get()) }
    single<CatalogProvider> {
        val context = androidContext()
        CatalogProvider { root -> openCatalog(context, root) }
    }
}

private fun openCatalog(context: Context, vaultRoot: Path): Outcome<CatalogStore> =
    try {
        // Absolute catalog path under the backup-excluded vault root (db/ created by the resolver).
        // The instrumented test verifies the DB actually lands at <filesDir>/vault/db/.
        val dbPath = Path(Path(vaultRoot, "db"), "catalog.db").toString()
        CatalogStore.create(DriverFactory(context, dbPath).create())
    } catch (e: Exception) {
        Outcome.Err(VaultError.Db(e))
    }
