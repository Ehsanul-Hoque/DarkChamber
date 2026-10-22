package dev.ehsanfahad.darkchamber.app.di

import coil3.PlatformContext
import dev.ehsanfahad.darkchamber.app.IosVaultRootResolver
import dev.ehsanfahad.darkchamber.app.VaultRootResolver
import dev.ehsanfahad.darkchamber.app.auth.IosBiometricAuthenticator
import dev.ehsanfahad.darkchamber.app.camera.IosCameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.app.picker.IosFilePicker
import dev.ehsanfahad.darkchamber.app.picker.IosPhotoPicker
import dev.ehsanfahad.darkchamber.app.system.IosSecretClipboard
import dev.ehsanfahad.darkchamber.app.thumbnail.IosThumbnailEncoder
import dev.ehsanfahad.darkchamber.app.system.SecretClipboard
import dev.ehsanfahad.darkchamber.app.rootViewController
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.crypto.IosSecureKeyStore
import dev.ehsanfahad.darkchamber.core.crypto.SecureKeyStore
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.db.DriverFactory
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.getOrNull
import dev.ehsanfahad.darkchamber.core.vault.ThumbnailEncoder
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * iOS composition root. Binds the platform-resolved vault root (from `NSDocumentDirectory`,
 * backup-excluded), the Keychain-backed `SecureKeyStore`, and the catalog opener (real
 * `NativeSqliteDriver` under `<root>/db/catalog.db`).
 */
actual fun platformModule(): Module = module {
    single<VaultRootResolver> { IosVaultRootResolver() }
    single<SecureKeyStore> { IosSecureKeyStore() }
    single<BiometricAuthenticator> { IosBiometricAuthenticator() }
    // The vault feature's picker seams, presented from the app's single Compose host. Neither takes
    // a SystemUiHandoff: both present inside our own window, so the app never backgrounds mid-pick
    // the way it does on Android.
    single<FilePicker> { IosFilePicker(::rootViewController) }
    // The photo picker needs the vault's wiped-on-lock tmp/ for the one branch where the system
    // hands back a copy instead of the original. Resolved per pick, not at graph construction: the
    // root is created with the vault, and the picker outlives no session anyway.
    single<PhotoPicker> {
        val rootResolver = get<VaultRootResolver>()
        IosPhotoPicker(::rootViewController) { rootResolver.resolve().getOrNull()?.let { Path(it, "tmp") } }
    }
    // The camera feature's seam. Presented inside our own window, like the pickers; the permission
    // alert is a system alert over it, which makes the app inactive rather than backgrounded.
    single<CameraController> { IosCameraController() }
    // Coil's context carries nothing on iOS, but the loader still asks for one.
    single<PlatformContext> { PlatformContext.INSTANCE }
    // Decodes a preview during import. Stateless: ImageIO works on the bytes the vault hands it,
    // so unlike the pickers there is no view controller to reach for.
    single<ThumbnailEncoder> { IosThumbnailEncoder() }
    // Local-only and self-expiring; see the implementation for what a plain pasteboard write costs.
    single<SecretClipboard> { IosSecretClipboard() }
    single<CatalogProvider> { CatalogProvider { root -> openCatalog(root) } }
}

private fun openCatalog(vaultRoot: Path): Outcome<CatalogStore> =
    try {
        val dbPath = Path(Path(vaultRoot, "db"), "catalog.db").toString()
        CatalogStore.create(DriverFactory(dbPath).create())
    } catch (e: Exception) {
        Outcome.Err(VaultError.Db(e))
    }
