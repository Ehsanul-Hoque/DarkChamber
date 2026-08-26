package dev.ehsanfahad.darkchamber.app.di

import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Opens the catalog over an already-resolved vault root. The SQLDelight driver is platform-bound
 * and its path depends on the resolved root, so creation is deferred behind this seam and supplied
 * by the platform Koin module — `commonMain` never names a `SqlDriver`.
 */
fun interface CatalogProvider {
    fun open(vaultRoot: Path): Outcome<CatalogStore>
}

/**
 * The platform-agnostic half of the composition root (D2): the crypto engine and the
 * [VaultBootstrap] that composes the resolved root, catalog, storage, and session. The
 * platform-bound bindings ([dev.ehsanfahad.darkchamber.app.VaultRootResolver], [CatalogProvider],
 * and the `SecureKeyStore`) come from [platformModule].
 */
val commonAppModule: Module = module {
    // The one sanctioned cryptographic primitive seam (hard rule #2).
    single<CryptoEngine> { CryptographyKotlinEngine() }

    single {
        VaultBootstrap(
            rootResolver = get(),
            engine = get(),
            catalogProvider = get(),
            secureKeyStore = get(),
        )
    }
}

/** The platform-bound half of the composition root — bound in `androidMain` / `iosMain`. */
expect fun platformModule(): Module

/** The full module set the launchers hand to `startKoin`: common + platform. */
fun appModules(): List<Module> = listOf(commonAppModule, platformModule())
