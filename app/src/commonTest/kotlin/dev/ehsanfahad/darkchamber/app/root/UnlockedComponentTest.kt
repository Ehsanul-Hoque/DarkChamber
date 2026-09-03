package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.stop
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.app.testSqlDriver
import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lock teardown: manual `lock()` and Essenty `onStop` both run — exactly once — the full
 * sequence: zeroize the MK, purge `tmp/` scratch, close the catalog driver, dispose the session
 * scope, and signal the gate. No driver is left open on lock.
 */
class UnlockedComponentTest {
    private val testDir = Path(SystemTemporaryDirectory, "uct-${Random.nextULong()}")
    // The session scope builds a VaultObjects that resolves the CryptoEngine root single (as the
    // real graph provides it), so the test Koin registers it too.
    private val koin: Koin = koinApplication {
        modules(module { single<CryptoEngine> { CryptographyKotlinEngine() } })
    }.koin

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    @Test
    fun manual_lock_zeroizes_purges_closes_and_signals() {
        var locked = false
        val vault = openVault()
        val strayScratch = Path(Path(vault.storageRoot, "tmp"), "scratch-stray")
        SystemFileSystem.sink(strayScratch).buffered().use { it.writeString("decrypted-scratch") }

        val component = DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { resume() }),
            vault = vault.open,
            koin = koin,
            onLock = { locked = true },
        )

        component.lock()

        assertTrue(locked, "lock must signal the gate (onLock)")
        assertFailsWith<IllegalStateException>("MK must be zeroized — post-close ops throw") {
            vault.open.session.generateDek()
        }
        assertFalse(SystemFileSystem.exists(strayScratch), "tmp/ scratch must be purged on lock")
        assertTrue(vault.open.catalog.countItems() is Outcome.Err, "catalog driver must be closed")
    }

    @Test
    fun background_on_stop_triggers_the_same_lock() {
        var locked = false
        val vault = openVault()
        val lifecycle = LifecycleRegistry().apply { resume() }

        DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(lifecycle),
            vault = vault.open,
            koin = koin,
            onLock = { locked = true },
        )

        lifecycle.stop() // app backgrounded

        assertTrue(locked, "onStop must lock the session")
        assertFailsWith<IllegalStateException> { vault.open.session.generateDek() }
    }

    @Test
    fun teardown_runs_only_once() {
        var lockCount = 0
        val vault = openVault()
        val component = DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { resume() }),
            vault = vault.open,
            koin = koin,
            onLock = { lockCount++ },
        )

        component.lock()
        component.lock() // idempotent — the guard prevents a second teardown/signal

        assertTrue(lockCount == 1, "teardown must run once, was $lockCount")
    }

    private class Vault(val open: VaultBootstrap.OpenVault, val storageRoot: Path)

    private fun openVault(): Vault {
        val engine = CryptographyKotlinEngine()
        val cheap = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)
        val session: VaultSession =
            VaultSession.createVault(engine, DefaultKeyDerivation(engine), "pw".encodeToByteArray(), cheap).session
        val root = Path(testDir, "vault-${Random.nextULong()}")
        SystemFileSystem.createDirectories(Path(root, "tmp"))
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        return Vault(VaultBootstrap.OpenVault(session, VaultStorage(root), catalog), root)
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
