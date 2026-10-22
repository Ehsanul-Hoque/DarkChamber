package dev.ehsanfahad.darkchamber.feature.camera.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.camera.FakeCameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraComponent
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraDestination
import dev.ehsanfahad.darkchamber.feature.camera.cameraComponent
import dev.ehsanfahad.darkchamber.feature.camera.testSqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The camera saving into a **real** vault — a live session, real storage, a real catalog — through
 * the public factory, so what is pinned here is the wiring the host gets: that a photo lands where
 * the camera was opened from and the bar's count follows it, and that a lock leaves nothing behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraVaultTest {

    private val root = Path(SystemTemporaryDirectory, "camera-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()

    // Deliberately weak Argon2 parameters: these tests are about the save, not the KDF.
    private val session: VaultSession = VaultSession.createVault(
        engine,
        DefaultKeyDerivation(engine),
        "pw".encodeToByteArray(),
        PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1),
    ).session

    private lateinit var catalog: CatalogStore

    @AfterTest
    fun cleanUp() {
        if (::catalog.isInitialized) catalog.close()
        session.close()
        deleteRecursively(root)
    }

    private fun TestScope.vault(): VaultObjects {
        SystemFileSystem.createDirectories(Path(root, "tmp"))
        // The catalog's reactive mapping and the listing's decrypt both run on the test scheduler, so
        // a count is read when it is published rather than whenever a background thread gets there.
        val mapping = UnconfinedTestDispatcher(testScheduler)
        catalog = (CatalogStore.create(testSqlDriver(), mapping) as Outcome.Ok).value
        return VaultObjects(
            session = session,
            storage = VaultStorage(root),
            catalog = catalog,
            codec = DchmCodec(engine),
            clock = { 1_000L },
            random = engine::randomBytes,
            mapContext = mapping,
        )
    }

    private fun TestScope.camera(
        objects: VaultObjects,
        destination: CameraDestination,
        sessionJob: Job,
        ioContext: CoroutineContext = UnconfinedTestDispatcher(testScheduler),
    ): CameraComponent {
        val main = UnconfinedTestDispatcher(testScheduler)
        return cameraComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry().apply { resume() }),
            vaultObjects = objects,
            camera = FakeCameraController(),
            destination = destination,
            saveScope = CoroutineScope(main + sessionJob),
            unreadableNameLabel = "Name can't be read",
            onClose = {},
            mainContext = main,
            ioContext = ioContext,
            clock = { 2_000L },
        )
    }

    @Test
    fun a_photo_taken_in_an_album_is_filed_there_and_its_count_rises() = runTest {
        val objects = vault()
        val trip = (objects.createAlbum("Trip") as Outcome.Ok).value
        val sessionJob = Job()
        // Its own copy of the id, as a host decoding it from a navigation config would have.
        val camera = camera(objects, CameraDestination.Album(trip.copyOf()), sessionJob)
        assertEquals(0L, camera.state.value.count, "an empty album reads as empty, not unknown")

        camera.shoot()

        assertEquals(1L, camera.state.value.count, "the bar counts the photo once its row is in")
        val filed = objects.observeItems(ItemFilter.Album(trip)).first().single()
        assertContentEquals(filed.id, camera.state.value.queue.lastSavedId, "the tile shows that photo")
        assertEquals(0L, objects.observeItemCount(ItemFilter.Unfiled).first(), "and nothing landed unfiled")
        sessionJob.cancel()
    }

    @Test
    fun a_photo_taken_from_the_whole_vault_is_unfiled_and_counted() = runTest {
        val objects = vault()
        objects.createAlbum("Trip")
        val sessionJob = Job()
        val camera = camera(objects, CameraDestination.AllItems, sessionJob)
        assertEquals(0L, camera.state.value.count)

        camera.shoot()

        assertEquals(1L, camera.state.value.count)
        assertEquals(1L, objects.observeItemCount(ItemFilter.Unfiled).first(), "the photo files nowhere")
        sessionJob.cancel()
    }

    /**
     * The lock lands with one photo handed to the encrypt and a second waiting behind it. The lock
     * cancels the session's feature scope and then closes the session, as the host does — and the
     * vault ends with no item, no object and no scratch file.
     */
    @Test
    fun a_lock_mid_queue_leaves_nothing_in_the_vault() = runTest {
        val objects = vault()
        val sessionJob = Job()
        val worker = GateDispatcher()
        val camera = camera(objects, CameraDestination.AllItems, sessionJob, ioContext = worker)

        camera.shoot()
        camera.shoot()
        assertEquals(1, worker.pending, "the first photo is waiting on the encrypt")
        assertEquals(2, camera.state.value.queue.holding, "and the second is held behind it")

        sessionJob.cancel()
        worker.runAll()
        session.close()

        assertEquals(0L, objects.observeItemCount(ItemFilter.All).first(), "no item")
        assertTrue(filesUnder(root).isEmpty(), "no object and no scratch: ${filesUnder(root)}")
    }

    /** Runs what it is handed only when the test says so — the encrypt, held at its doorstep. */
    private class GateDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        val pending: Int get() = tasks.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private fun filesUnder(path: Path): List<Path> {
        val md = SystemFileSystem.metadataOrNull(path) ?: return emptyList()
        if (!md.isDirectory) return listOf(path)
        return SystemFileSystem.list(path).flatMap(::filesUnder)
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
