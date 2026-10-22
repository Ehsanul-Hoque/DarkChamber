package dev.ehsanfahad.darkchamber.app.root

import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.create
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.stop
import dev.ehsanfahad.darkchamber.app.FakeFilePicker
import dev.ehsanfahad.darkchamber.app.FakePhotoPicker
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Locking has to empty the decrypted previews the image loader is holding.
 *
 * Disabling the disk cache keeps plaintext off storage; it says nothing about memory. The loader's
 * memory cache exists precisely so a scroll does not re-decrypt, which means that after a lock —
 * MK zeroized, scratch purged, catalog closed — a live cache would still be handing out decrypted
 * pictures of the user's files. Nothing else in the codebase would notice: the app looks locked.
 *
 * It runs on the simulator because a real `ImageLoader` needs a `PlatformContext`, which on Android
 * *is* a `Context` and cannot be produced by a host test. The teardown under test is common code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionImageCacheTest {
    private val testDir = Path(SystemTemporaryDirectory, "sic-${Random.nextULong()}")

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    @Test
    fun locking_empties_the_decrypted_image_cache() {
        val component = unlocked()
        val loader = assertNotNull(component.imageLoader, "the simulator graph does bind a context")
        val key = loader.cacheOnePreview()

        component.lock()

        assertNull(loader.memoryCache?.get(key), "a decrypted preview outlived the lock")
        assertEquals(0, loader.memoryCache?.size, "and the cache still holds bytes")
    }

    @Test
    fun backgrounding_empties_it_the_same_way() {
        val lifecycle = LifecycleRegistry().apply { resume() }
        val component = unlocked(lifecycle = lifecycle)
        val loader = assertNotNull(component.imageLoader)
        val key = loader.cacheOnePreview()

        // The path that matters most in practice: the user switches apps rather than tapping lock,
        // and it is also the path where a composition-driven clear would never run.
        lifecycle.stop()

        assertNull(loader.memoryCache?.get(key), "background lock must clear it too")
    }

    @Test
    fun destroying_the_subtree_empties_it_without_a_lock_ever_being_asked_for() {
        val lifecycle = LifecycleRegistry().apply { resume() }
        val component = unlocked(lifecycle = lifecycle)
        val loader = assertNotNull(component.imageLoader)
        val key = loader.cacheOnePreview()

        // The last-resort path: the subtree is destroyed without anyone calling lock(). The loader
        // becomes garbage either way, but "eventually collected" is not a security property — the
        // decrypted bytes are readable until it happens.
        lifecycle.destroy()

        assertNull(loader.memoryCache?.get(key), "destroy must clear it as thoroughly as a lock")
    }

    @Test
    fun a_subtree_destroyed_before_it_was_ever_resumed_is_still_emptied() {
        // The one path that reaches teardown without `lock()` running: no resume means no stop, so
        // the auto-lock hook never fires and only `doOnDestroy` is left. It is why the clear lives
        // in the shared teardown rather than on the lock path — every other route happens to pass
        // through both.
        val lifecycle = LifecycleRegistry().apply { create() }
        val component = unlocked(lifecycle = lifecycle)
        val loader = assertNotNull(component.imageLoader)
        val key = loader.cacheOnePreview()

        lifecycle.destroy()

        assertNull(loader.memoryCache?.get(key))
    }

    @Test
    fun a_session_that_never_drew_a_tile_builds_no_loader_at_lock() {
        var contextResolutions = 0
        val component = unlocked(onContextResolved = { contextResolutions++ })

        component.lock()

        // Touching the loader during teardown would construct one for a session that never showed
        // the gallery — allocating a cache in order to empty it.
        assertEquals(0, contextResolutions, "lock must not build an image loader to clear it")
    }

    /** Puts one entry in the loader's memory cache and returns its key. */
    private fun ImageLoader.cacheOnePreview(): MemoryCache.Key {
        val key = MemoryCache.Key("decrypted-preview")
        val cache = assertNotNull(memoryCache, "the gallery's loader keeps a memory cache")
        cache[key] = MemoryCache.Value(BlankImage)
        assertNotNull(cache[key], "the fixture itself must land, or the test proves nothing")
        return key
    }

    /** The smallest thing that satisfies Coil's cache; the test never draws it. */
    private object BlankImage : Image {
        override val size: Long = 1L
        override val width: Int = 1
        override val height: Int = 1
        override val shareable: Boolean = true

        override fun draw(canvas: coil3.Canvas) = Unit
    }

    private fun unlocked(
        lifecycle: LifecycleRegistry = LifecycleRegistry().apply { resume() },
        onContextResolved: () -> Unit = {},
    ): DefaultUnlockedComponent {
        val graph: Koin = koinApplication {
            modules(
                module {
                    single<CryptoEngine> { CryptographyKotlinEngine() }
                    single<FilePicker> { FakeFilePicker() }
                    single<PhotoPicker> { FakePhotoPicker() }
                    single<PlatformContext> {
                        onContextResolved()
                        PlatformContext.INSTANCE
                    }
                },
            )
        }.koin
        val dispatcher = UnconfinedTestDispatcher()
        return DefaultUnlockedComponent(
            componentContext = DefaultComponentContext(lifecycle),
            vault = openVault(),
            koin = graph,
            mainContext = dispatcher,
            ioContext = dispatcher,
            notSavedRecord = NotSavedRecord(),
            onLock = {},
            onLeave = {},
        )
    }

    private fun openVault(): VaultBootstrap.OpenVault {
        val engine = CryptographyKotlinEngine()
        val cheap = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)
        val session: VaultSession =
            VaultSession.createVault(engine, DefaultKeyDerivation(engine), "pw".encodeToByteArray(), cheap).session
        val root = Path(testDir, "vault-${Random.nextULong()}")
        SystemFileSystem.createDirectories(Path(root, "tmp"))
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        return VaultBootstrap.OpenVault(session, VaultStorage(root), catalog)
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
