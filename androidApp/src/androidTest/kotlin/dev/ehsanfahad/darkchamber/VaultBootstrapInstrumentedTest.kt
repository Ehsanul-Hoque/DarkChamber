package dev.ehsanfahad.darkchamber

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.app.di.appModules
import dev.ehsanfahad.darkchamber.core.model.Outcome
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import java.io.File

/**
 * Cycle 5a acceptance (D3): boots the real Koin graph on a device/emulator with a real
 * `Context` — real `filesDir` vault root, real `AndroidSqliteDriver`, real Keystore-backed
 * `SecureKeyStore` — cold-opens the vault, round-trips one encrypted object, and spot-checks
 * hard rule #1 (no plaintext to shared/external storage).
 *
 * Runnable locally: `./gradlew :androidApp:connectedFossDebugAndroidTest` (needs a device or
 * emulator). Not wired into CI this cycle — the instrumented/quality job is still deferred.
 */
@RunWith(AndroidJUnit4::class)
class VaultBootstrapInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun coldOpen_roundTrips_and_keeps_all_plaintext_app_private() {
        val vaultRoot = File(context.filesDir, "vault")
        vaultRoot.deleteRecursively() // deterministic create path

        val koin = koinApplication {
            androidContext(context)
            modules(appModules())
        }
        try {
            val bootstrap = koin.koin.get<VaultBootstrap>()

            // First run: create-vault + encrypted round-trip. Second: reopen via the persisted
            // keystore (unlock path). selfCheck() uses a fixed diagnostic passphrase both times.
            assertTrue("first selfCheck (create) failed", bootstrap.selfCheck() is Outcome.Ok)
            assertTrue("second selfCheck (reopen) failed", bootstrap.selfCheck() is Outcome.Ok)

            // Catalog + wrapped-key blob live under the app-private, backup-denied vault root.
            assertTrue("catalog.db missing", File(vaultRoot, "db/catalog.db").exists())
            assertTrue("keystore blob missing", File(vaultRoot, "keys/mk.wrapped").exists())

            // At least one encrypted object blob was written (sharded objects/ab/cd/<id>.dchm).
            val encryptedObject = File(vaultRoot, "objects")
                .walkTopDown().any { it.isFile && it.name.endsWith(".dchm") }
            assertTrue("no encrypted .dchm object written", encryptedObject)

            // Hard rule #1 spot-check: nothing landed in external/shared storage.
            assertNoExternalArtifacts()
        } finally {
            koin.close()
            vaultRoot.deleteRecursively()
        }
    }

    private fun assertNoExternalArtifacts() {
        val externalDirs = listOfNotNull(context.externalCacheDir, context.getExternalFilesDir(null))
        externalDirs.forEach { dir ->
            val leaked = dir.walkTopDown().filter { it.isFile }.map { it.name }.toList()
            assertTrue("external storage leaked files: $leaked", leaked.isEmpty())
        }
    }
}
