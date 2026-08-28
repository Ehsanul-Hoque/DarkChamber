package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.app.di.appModules
import dev.ehsanfahad.darkchamber.core.model.Outcome
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.koin.dsl.koinApplication
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cycle 5a/5b acceptance on the iOS simulator — the iOS-runtime-specific glue no common test can
 * reach: [IosVaultRootResolver] resolves the real `<Documents>/vault` root and sets
 * `isExcludedFromBackup = true` (the deferred cycle-3 runtime call), and the full real graph
 * (Documents root + native SQLite driver + Keychain `SecureKeyStore`) create-then-unlocks a vault by
 * passphrase — the hardware/biometric slot is untouched (device-only). Runs via
 * `./gradlew :app:iosSimulatorArm64Test`.
 */
class IosVaultRootResolverTest {
    @Test
    fun resolve_marks_the_documents_vault_root_excluded_from_backup() {
        val outcome = IosVaultRootResolver().resolve()

        assertTrue(outcome is Outcome.Ok, "resolve failed: $outcome")
        assertTrue(
            isExcludedFromBackup(outcome.value.toString()),
            "vault root is not isExcludedFromBackup — the cycle-3 runtime call did not take",
        )
    }

    @Test
    fun real_ios_graph_creates_then_unlocks_the_vault_on_the_simulator() {
        // The simulator's Documents dir persists across runs — start clean so create is deterministic.
        cleanVaultRoot()

        buildGraph().use { koin ->
            val created = koin.get<VaultBootstrap>().createVault(passphrase())
            assertTrue(created is Outcome.Ok, "iOS create failed: $created")
            created.value.vault.session.close()
        }
        buildGraph().use { koin ->
            val unlocked = koin.get<VaultBootstrap>().unlock(passphrase())
            assertTrue(unlocked is Outcome.Ok, "iOS unlock failed: $unlocked")
            unlocked.value.session.close()
        }
    }

    private fun passphrase(): ByteArray = "correct-horse-battery".encodeToByteArray()

    // A tiny KoinApplication holder so each build is disposed (no leaked container — 5a #3 spirit).
    private class Graph(private val app: org.koin.core.KoinApplication) {
        inline fun <T> use(block: (org.koin.core.Koin) -> T): T = try {
            block(app.koin)
        } finally {
            app.close()
        }
    }

    private fun buildGraph(): Graph = Graph(koinApplication { modules(appModules()) })

    private fun cleanVaultRoot() {
        val resolved = IosVaultRootResolver().resolve()
        if (resolved is Outcome.Ok) deleteRecursively(resolved.value)
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun isExcludedFromBackup(path: String): Boolean = memScoped {
        val url = NSURL.fileURLWithPath(path)
        val holder = alloc<ObjCObjectVar<Any?>>()
        val ok = url.getResourceValue(holder.ptr, NSURLIsExcludedFromBackupKey, null)
        if (!ok) false else (holder.value as? NSNumber)?.boolValue ?: false
    }
}
