package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.stop
import dev.ehsanfahad.darkchamber.app.FakeFilePicker
import dev.ehsanfahad.darkchamber.app.FakePhotoPicker
import dev.ehsanfahad.darkchamber.app.FakePickedFile
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
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lock teardown: manual `lock()` and Essenty `onStop` both run — exactly once — the full
 * sequence: zeroize the MK, purge `tmp/` scratch, close the catalog driver, dispose the session
 * scope, and signal the gate. No driver is left open on lock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockedComponentTest {
    private val testDir = Path(SystemTemporaryDirectory, "uct-${Random.nextULong()}")
    // The session scope builds a VaultObjects that resolves the CryptoEngine root single (as the
    // real graph provides it), so the test Koin registers it too.
    private val koin: Koin = koinApplication {
        modules(
            module {
                single<CryptoEngine> { CryptographyKotlinEngine() }
                // Constructing the unlocked subtree constructs the gallery, which needs the picker.
                single<FilePicker> { FakeFilePicker() }
                single<PhotoPicker> { FakePhotoPicker() }
            },
        )
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
            mainContext = UnconfinedTestDispatcher(),
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
            mainContext = UnconfinedTestDispatcher(),
            onLock = { locked = true },
        )

        lifecycle.stop() // app backgrounded

        assertTrue(locked, "onStop must lock the session")
        assertFailsWith<IllegalStateException> { vault.open.session.generateDek() }
    }

    /**
     * The unlocked subtree opens on the gallery, and it is the only thing on the stack — a viewer
     * config joins it once the seekable read path exists.
     */
    @Test
    fun the_subtree_opens_on_the_gallery() {
        val vault = openVault()
        val component = DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { resume() }),
            vault = vault.open,
            koin = koin,
            mainContext = UnconfinedTestDispatcher(),
            onLock = {},
        )

        val stack = component.stack.value
        assertTrue(stack.active.instance is UnlockedChild.Gallery, "the gallery is the active child")
        assertTrue(stack.backStack.isEmpty(), "nothing sits behind it yet")

        component.lock()
    }

    /**
     * The gallery is built from the **session** scope, so it cannot exist without a live session —
     * the structural gate invariant, one level below the root.
     */
    @Test
    fun locking_disposes_the_scope_the_gallery_was_built_from() {
        val vault = openVault()
        val component = DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { resume() }),
            vault = vault.open,
            koin = koin,
            mainContext = UnconfinedTestDispatcher(),
            onLock = {},
        )
        assertTrue(component.stack.value.active.instance is UnlockedChild.Gallery)

        component.lock()

        assertFailsWith<IllegalStateException>("the session backing the gallery is gone") {
            vault.open.session.generateDek()
        }
    }

    /**
     * Lock cancels feature coroutines **before** zeroizing the session they run against, so an
     * import that has not yet entered its blocking encrypt stops instead of running on against a
     * dead MK. Lock does not *wait* for one that has: `lock()` runs on the UI thread, so awaiting a
     * multi-second import would freeze the app and delay zeroizing the key.
     */
    @Test
    fun locking_cancels_feature_work_before_the_session_dies() {
        val vault = openVault()
        val picker = FakeFilePicker()
        val component = unlocked(vault, picker = picker)
        val gallery = (component.stack.value.active.instance as UnlockedChild.Gallery).component

        // Before the lock the scope runs: the picker is asked, cancels inline (empty list), and the
        // batch settles straight back to idle.
        gallery.importFiles()
        assertTrue(picker.calls == 1, "the intent reached the picker while unlocked")
        assertFalse(gallery.state.value.importing, "and ran to completion")

        component.lock()

        // After the lock the batch coroutine never starts, so the picker is never even consulted —
        // the feature scope died before the session it would have imported into.
        gallery.importFiles()
        assertTrue(picker.calls == 1, "a cancelled scope never opened a second picker, was ${picker.calls}")
        assertFailsWith<IllegalStateException> { vault.open.session.generateDek() }
    }

    /**
     * The companion to the decision that an import outlives a screen (spec D7): it does **not**
     * outlive the session. Locking mid-batch stops it, and everything imported before the lock is
     * a complete, catalogued item — a batch is not a transaction, and rolling those back would
     * destroy data the user asked for.
     */
    @Test
    fun locking_mid_batch_stops_it_and_keeps_what_was_already_imported() {
        val vault = openVault()
        lateinit var component: DefaultUnlockedComponent
        // The lock fires as the *second* file is opened for reading, so one import has completed and
        // one is in flight.
        val third = FakePickedFile(name = "third.bin")
        val picker = FakeFilePicker(
            listOf(
                FakePickedFile(name = "first.bin"),
                FakePickedFile(name = "second.bin", onOpen = { component.lock() }),
                third,
            ),
        )
        component = unlocked(vault, picker = picker)
        val gallery = (component.stack.value.active.instance as UnlockedChild.Gallery).component

        gallery.importFiles()

        // The catalog is closed with the session, so count the object blobs the vault kept.
        val objects = Path(vault.storageRoot, "objects")
        assertTrue(dchmCount(objects) == 1, "one complete object survives, found ${dchmCount(objects)}")
        assertFalse(third.opened, "the batch stopped at the lock instead of running on")
        assertTrue(third.closed, "and the grant it never used was released")
        assertEquals(emptyList(), SystemFileSystem.list(Path(vault.storageRoot, "tmp")).toList(), "no scratch")
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
            mainContext = UnconfinedTestDispatcher(),
            onLock = { lockCount++ },
        )

        component.lock()
        component.lock() // idempotent — the guard prevents a second teardown/signal

        assertTrue(lockCount == 1, "teardown must run once, was $lockCount")
    }

    private fun unlocked(
        vault: Vault,
        picker: FakeFilePicker = FakeFilePicker(),
        onLock: () -> Unit = {},
    ): DefaultUnlockedComponent {
        // A graph of its own so a test can hand in its own picker; everything else matches `koin`.
        val graph = koinApplication {
            modules(
                module {
                    single<CryptoEngine> { CryptographyKotlinEngine() }
                    single<FilePicker> { picker }
                    single<PhotoPicker> { FakePhotoPicker() }
                },
            )
        }.koin
        val dispatcher = UnconfinedTestDispatcher()
        return DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { resume() }),
            vault = vault.open,
            koin = graph,
            mainContext = dispatcher,
            // Inline, so a batch driven from a test runs to completion before the assertions.
            ioContext = dispatcher,
            onLock = onLock,
        )
    }

    private fun dchmCount(root: Path): Int {
        val md = SystemFileSystem.metadataOrNull(root) ?: return 0
        if (!md.isDirectory) return if (root.name.endsWith(".dchm")) 1 else 0
        return SystemFileSystem.list(root).sumOf { dchmCount(it) }
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
