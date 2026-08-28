package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.app.testSqlDriver
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The structural session gate: the root starts Locked; a live vault fed through the locked child's
 * `onUnlocked` hook transitions to Unlocked holding that exact session; the unlocked child's `lock()`
 * returns to Locked. Pure component logic — no Compose (TASK-010) and no DI (TASK-008).
 */
class RootComponentTest {
    private val testDir = Path(SystemTemporaryDirectory, "rct-${Random.nextULong()}")

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun root(): DefaultRootComponent =
        DefaultRootComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            lockedFactory = { ctx, onUnlocked -> DefaultLockedComponent(ctx, LockMode.Unlock, onUnlocked) },
            unlockedFactory = { ctx, vault, onLock -> DefaultUnlockedComponent(ctx, vault, onLock) },
        )

    @Test
    fun starts_locked() {
        assertIs<RootChild.Locked>(root().child.value)
    }

    @Test
    fun feeding_a_vault_transitions_to_unlocked_holding_that_session() {
        val root = root()
        val vault = openVault()

        val locked = assertIs<RootChild.Locked>(root.child.value)
        (locked.component as DefaultLockedComponent).completeUnlock(vault)

        val unlocked = assertIs<RootChild.Unlocked>(root.child.value)
        assertSame(vault.session, unlocked.component.session, "the unlocked child must hold the fed session")
    }

    @Test
    fun lock_returns_to_locked_and_replaces_the_subtree() {
        val root = root()
        (assertIs<RootChild.Locked>(root.child.value).component as DefaultLockedComponent)
            .completeUnlock(openVault())
        val unlocked = assertIs<RootChild.Unlocked>(root.child.value)

        unlocked.component.lock()

        val relocked = assertIs<RootChild.Locked>(root.child.value)
        assertTrue(relocked.component !== unlocked.component, "lock must build a fresh locked subtree")
    }

    // A live vault with a cheap real session (Argon2id m=256KiB/t=1) + temp storage + in-memory catalog.
    private fun openVault(): VaultBootstrap.OpenVault {
        val engine = CryptographyKotlinEngine()
        val cheap = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)
        val session: VaultSession =
            VaultSession.createVault(engine, DefaultKeyDerivation(engine), "pw".encodeToByteArray(), cheap).session
        val root = Path(testDir, "vault-${Random.nextULong()}")
        SystemFileSystem.createDirectories(root)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        return VaultBootstrap.OpenVault(session, VaultStorage(root), catalog)
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
