package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.app.di.CatalogProvider
import dev.ehsanfahad.darkchamber.app.di.commonAppModule
import dev.ehsanfahad.darkchamber.core.crypto.SecureKeyStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * The composition root resolves: `commonAppModule` + a test platform module (fake keystore, stub
 * resolver, uninvoked catalog opener) construct a [VaultBootstrap]. The real platform bindings
 * (Context / Documents / Keystore / driver) are proven by the instrumented tests (TASK-008/009);
 * this only asserts the graph wires up. The round-trip behaviour lands in TASK-005.
 */
class CompositionRootTest {
    private val testPlatformModule: Module = module {
        single<VaultRootResolver> {
            object : VaultRootResolver {
                override fun resolve(): Outcome<Path> =
                    Outcome.Err(VaultError.Io(IllegalStateException("stub resolver not invoked")))
            }
        }
        single<SecureKeyStore> { FakeSecureKeyStore() }
        single<CatalogProvider> {
            CatalogProvider { error("catalog opener not invoked during graph construction") }
        }
    }

    @Test
    fun graph_resolves_vault_bootstrap() {
        val app = koinApplication { modules(commonAppModule, testPlatformModule) }
        try {
            assertNotNull(app.koin.get<VaultBootstrap>())
        } finally {
            app.close()
        }
    }
}
