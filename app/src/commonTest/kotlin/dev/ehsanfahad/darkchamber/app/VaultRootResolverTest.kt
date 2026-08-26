package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host/sim-testable core of vault-root resolution: [DirectoryVaultRootResolver] creates
 * `<root>` + `<root>/db/` and runs the platform backup-exclusion hook. The real Context /
 * Documents / isExcludedFromBackup wiring is proven by the instrumented tests (TASK-008/009).
 */
class VaultRootResolverTest {
    private val testDir = Path(SystemTemporaryDirectory, "vrr-test-${Random.nextULong()}")
    private val vaultRoot = Path(testDir, "vault")

    @AfterTest
    fun cleanup() {
        deleteRecursively(testDir)
    }

    @Test
    fun resolve_creates_root_and_db_and_returns_the_root() {
        val outcome = DirectoryVaultRootResolver(vaultRoot).resolve()

        assertTrue(outcome is Outcome.Ok, "resolve() failed: $outcome")
        assertEquals(vaultRoot, outcome.value)
        assertTrue(SystemFileSystem.exists(vaultRoot), "vault root not created")
        assertTrue(SystemFileSystem.exists(Path(vaultRoot, "db")), "db/ not created")
    }

    @Test
    fun resolve_is_idempotent_over_an_existing_root() {
        assertTrue(DirectoryVaultRootResolver(vaultRoot).resolve() is Outcome.Ok)
        assertTrue(DirectoryVaultRootResolver(vaultRoot).resolve() is Outcome.Ok)
    }

    @Test
    fun resolve_runs_the_backup_exclusion_hook_with_the_root() {
        var seen: Path? = null
        val outcome = DirectoryVaultRootResolver(vaultRoot, excludeFromBackup = {
            seen = it
            Outcome.Ok(Unit)
        }).resolve()

        assertTrue(outcome is Outcome.Ok)
        assertEquals(vaultRoot, seen)
    }

    @Test
    fun resolve_surfaces_a_backup_exclusion_failure_as_err() {
        val outcome = DirectoryVaultRootResolver(vaultRoot, excludeFromBackup = {
            Outcome.Err(VaultError.Io(IllegalStateException("exclusion failed")))
        }).resolve()

        assertTrue(outcome is Outcome.Err)
        assertTrue(outcome.error is VaultError.Io)
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) {
            for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        }
        SystemFileSystem.delete(path, mustExist = false)
    }
}
