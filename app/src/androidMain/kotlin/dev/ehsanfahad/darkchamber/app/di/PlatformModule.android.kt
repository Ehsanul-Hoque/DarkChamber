package dev.ehsanfahad.darkchamber.app.di

import android.content.Context
import dev.ehsanfahad.darkchamber.app.AndroidVaultRootResolver
import dev.ehsanfahad.darkchamber.app.VaultRootResolver
import dev.ehsanfahad.darkchamber.app.auth.ActivityHolder
import dev.ehsanfahad.darkchamber.app.auth.AndroidBiometricAuthenticator
import dev.ehsanfahad.darkchamber.app.auth.DefaultActivityHolder
import dev.ehsanfahad.darkchamber.app.camera.AndroidCameraController
import dev.ehsanfahad.darkchamber.app.camera.AndroidFreeSpace
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.FreeSpace
import dev.ehsanfahad.darkchamber.app.picker.AndroidFilePicker
import dev.ehsanfahad.darkchamber.app.picker.AndroidPhotoPicker
import dev.ehsanfahad.darkchamber.app.system.AndroidSecretClipboard
import dev.ehsanfahad.darkchamber.app.thumbnail.AndroidThumbnailEncoder
import dev.ehsanfahad.darkchamber.app.system.SecretClipboard
import dev.ehsanfahad.darkchamber.core.crypto.AndroidSecureKeyStore
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.crypto.SecureKeyStore
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.db.DriverFactory
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.ThumbnailEncoder
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
    // The camera feature's seam. The application context for CameraX itself; the current activity
    // only for what needs one — the permission prompt, its rationale flag, and the settings page.
    single<CameraController> { AndroidCameraController(androidContext(), { get<ActivityHolder>().activity }) }
    // What a recording is sized from, read on the vault's own volume.
    single<FreeSpace> { AndroidFreeSpace(androidContext()) }

    // ---------------------------------------------------------------------------------------------
    // No `PlatformContext` binding here, deliberately — this note stands in for the line that used
    // to be, because the line looked entirely reasonable.
    //
    // On Android `coil3.PlatformContext` is an alias for `android.content.Context`, so
    // `single<PlatformContext> { androidContext() }` declares a definition keyed on `Context` whose
    // body asks Koin for a `Context` — itself. It also overrides the application context that
    // `androidContext(app)` registered at startup, so the first resolution needing a context recurses
    // until the stack ends: a fresh install crashed on launch, before the gate drew anything, because
    // the vault root resolver asks for one.
    //
    // Nothing is needed in its place. `androidContext(app)` already registers the application as a
    // `Context`, and that *is* a `PlatformContext`, so the session's `getOrNull<PlatformContext>()`
    // resolves it. Only iOS needs a binding, because there the type is Coil's own. A Konsist rule
    // fails the build if one reappears in this source set.
    // ---------------------------------------------------------------------------------------------

    // Decodes a preview during import. Stateless and context-free — BitmapFactory works on the
    // stream the vault hands it, so nothing platform-scoped has to be threaded in here.
    single<ThumbnailEncoder> { AndroidThumbnailEncoder() }
    // The application context, not an activity: the scheduled clear outlives the screen that copied.
    single<SecretClipboard> { AndroidSecretClipboard(androidContext()) }
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
