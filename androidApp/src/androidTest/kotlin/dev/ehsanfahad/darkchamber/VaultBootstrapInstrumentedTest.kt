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
 * Instrumented acceptance: boots the real Koin graph on a device/emulator with a real `Context` — real
 * `filesDir` vault root, real `AndroidSqliteDriver`, real Keystore-backed `SecureKeyStore` —
 * create-then-unlocks a vault **by passphrase** (the hardware/biometric slot needs a real prompt, so
 * it lives on the on-device checklist), and spot-checks hard rule #1 (no plaintext to shared storage).
 *
 * Runnable locally: `./gradlew :androidApp:connectedFossDebugAndroidTest` (needs a device or
 * emulator). Not wired into CI yet — the instrumented/quality job is still deferred.
 */
@RunWith(AndroidJUnit4::class)
class VaultBootstrapInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun createThenUnlock_keepsAllPlaintextAppPrivate() {
        val vaultRoot = File(context.filesDir, "vault")
        vaultRoot.deleteRecursively() // deterministic create path

        val koin = koinApplication {
            androidContext(context)
            modules(appModules())
        }
        try {
            val bootstrap = koin.koin.get<VaultBootstrap>()

            // First run: create the vault (keystore persisted under the backup-denied root).
            val created = bootstrap.createVault("correct-horse-battery".encodeToByteArray())
            assertTrue("createVault failed: $created", created is Outcome.Ok)
            (created as Outcome.Ok).value.vault.session.close()

            // Reopen on the same root via the persisted keystore (passphrase unlock).
            val unlocked = bootstrap.unlock("correct-horse-battery".encodeToByteArray())
            assertTrue("unlock failed: $unlocked", unlocked is Outcome.Ok)
            (unlocked as Outcome.Ok).value.session.close()

            // Catalog + wrapped-key blob live under the app-private, backup-denied vault root.
            assertTrue("catalog.db missing", File(vaultRoot, "db/catalog.db").exists())
            assertTrue("keystore blob missing", File(vaultRoot, "keys/mk.wrapped").exists())

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
