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
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason
import dev.ehsanfahad.darkchamber.core.model.NotSavedReporter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.storage.ScratchFile
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.camera.FakeCameraController
import dev.ehsanfahad.darkchamber.feature.camera.GateDispatcher
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraComponent
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraDestination
import dev.ehsanfahad.darkchamber.feature.camera.api.CaptureMode
import dev.ehsanfahad.darkchamber.feature.camera.api.ClipFile
import dev.ehsanfahad.darkchamber.feature.camera.api.ClipFiles
import dev.ehsanfahad.darkchamber.feature.camera.cameraComponent
import dev.ehsanfahad.darkchamber.feature.camera.data.VaultObjectsCaptures
import dev.ehsanfahad.darkchamber.feature.camera.domain.VideoLane
import dev.ehsanfahad.darkchamber.feature.camera.domain.VideoRecorder
import dev.ehsanfahad.darkchamber.feature.camera.testSqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
            notSaved = { _, _, _ -> },
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

    /** A recorded clip as the app will hand one over: a real scratch file in the vault's `tmp/`. */
    private class ScratchClip(private val scratch: ScratchFile, val opened: () -> Unit = {}) : ClipFile {
        override val path: Path get() = scratch.path

        override fun open(): RawSource {
            opened()
            return scratch.open()
        }

        override fun size(): Long? = scratch.size()

        override fun delete() {
            scratch.delete()
        }
    }

    private fun TestScope.lane(objects: VaultObjects, sessionJob: Job, ioContext: CoroutineContext, reports: MutableList<NotSavedReason>) =
        VideoLane(
            vault = VaultObjectsCaptures(objects),
            clock = { 3_000L },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + sessionJob),
            ioContext = ioContext,
            notSaved = { _, reason, _ -> reports += reason },
        )

    /**
     * The clip is encrypted straight from its own file, opened once per pass — never spilled into a
     * second plaintext copy — and once it is in the vault, `tmp/` is empty.
     */
    @Test
    fun a_clip_is_encrypted_from_its_file_without_a_second_copy_and_the_file_removed() = runTest {
        val objects = vault()
        val sessionJob = Job()
        val tmp = Path(root, "tmp")
        val bytes = Random.nextBytes(300_000)
        val scratch = VaultStorage(root).newScratchFile(suffix = ".mp4")
        SystemFileSystem.sink(scratch.path).buffered().use { it.write(bytes) }
        val seenInTmp = mutableListOf<List<Path>>()
        val clip = ScratchClip(scratch, opened = { seenInTmp += filesUnder(tmp) })

        lane(objects, sessionJob, UnconfinedTestDispatcher(testScheduler), mutableListOf()).save(clip, albumId = null)

        assertTrue(seenInTmp.isNotEmpty() && seenInTmp.all { it == listOf(scratch.path) }, "only the clip was ever in tmp/: $seenInTmp")
        assertTrue(filesUnder(tmp).isEmpty(), "and it is gone once saved")
        val item = objects.observeItems(ItemFilter.All).first().single()
        assertEquals("video/mp4", (objects.openMeta(item.id) as Outcome.Ok).value.mime)
        val decrypted = Buffer()
        assertIs<Outcome.Ok<Unit>>(objects.openObject(item.id, decrypted))
        assertContentEquals(bytes, decrypted.readByteArray(), "the vault holds the clip as recorded")
        sessionJob.cancel()
    }

    /** A lock with the clip at the encrypt's door: no item, no object, and no plaintext left in tmp/. */
    @Test
    fun a_lock_while_a_clip_waits_to_encrypt_leaves_nothing_in_the_vault() = runTest {
        val objects = vault()
        val sessionJob = Job()
        val worker = GateDispatcher()
        val reports = mutableListOf<NotSavedReason>()
        val scratch = VaultStorage(root).newScratchFile(suffix = ".mp4")
        SystemFileSystem.sink(scratch.path).buffered().use { it.write(Random.nextBytes(10_000)) }

        lane(objects, sessionJob, worker, reports).save(ScratchClip(scratch), albumId = null)
        assertEquals(1, worker.pending, "the clip is waiting on the encrypt")
        sessionJob.cancel()
        worker.runAll()
        session.close()

        assertEquals(0L, objects.observeItemCount(ItemFilter.All).first(), "no item")
        assertTrue(filesUnder(root).isEmpty(), "no object and no plaintext clip: ${filesUnder(root)}")
        assertEquals(listOf(NotSavedReason.Locked), reports)
    }

    private fun TestScope.recorder(objects: VaultObjects, sessionJob: Job, camera: FakeCameraController, reports: MutableList<NotSavedReason>): VideoRecorder {
        val storage = VaultStorage(root)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + sessionJob)
        val notSaved = NotSavedReporter { _, reason, _ -> reports += reason }
        val lane = VideoLane(VaultObjectsCaptures(objects), clock = { 3_000L }, scope = scope, ioContext = UnconfinedTestDispatcher(testScheduler), notSaved = notSaved)
        return VideoRecorder(
            camera = camera,
            lane = lane,
            // The platform writes the file as it records; this one is written the moment it is named.
            clips = ClipFiles {
                val scratch = storage.newScratchFile(suffix = ".mp4")
                SystemFileSystem.sink(scratch.path).buffered().use { it.write(Random.nextBytes(20_000)) }
                ScratchClip(scratch)
            },
            freeSpace = { 64_000_000_000L },
            albumId = null,
            scope = scope,
            notSaved = notSaved,
        )
    }

    private fun videoCamera() = FakeCameraController().apply {
        start()
        setMode(CaptureMode.Video)
    }

    /** A lock mid-recording: no item, no object, no thumbnail and no plaintext clip under the root. */
    @Test
    fun a_lock_while_recording_leaves_nothing_in_the_vault() = runTest {
        val objects = vault()
        val sessionJob = Job()
        val camera = videoCamera()
        val reports = mutableListOf<NotSavedReason>()
        val recorder = recorder(objects, sessionJob, camera, reports)
        assertTrue(recorder.start())
        assertTrue(filesUnder(Path(root, "tmp")).isNotEmpty(), "the clip is being written")

        sessionJob.cancel()
        camera.stop()
        session.close()

        assertEquals(0L, objects.observeItemCount(ItemFilter.All).first(), "no item")
        assertTrue(filesUnder(root).isEmpty(), "nothing at all under the root: ${filesUnder(root)}")
        assertEquals(listOf(NotSavedReason.Locked), reports)
    }

    /** A clip stopped normally, through the real vault: one item saved, and tmp/ empty after. */
    @Test
    fun a_stopped_recording_is_saved_and_leaves_tmp_empty() = runTest {
        val objects = vault()
        val sessionJob = Job()
        val recorder = recorder(objects, sessionJob, videoCamera(), mutableListOf())

        recorder.start()
        recorder.stop()

        assertEquals(1L, objects.observeItemCount(ItemFilter.All).first())
        assertTrue(filesUnder(Path(root, "tmp")).isEmpty(), "tmp/ is empty: ${filesUnder(Path(root, "tmp"))}")
        sessionJob.cancel()
    }

    /**
     * The crash cell: the process dies mid-recording, so no cleanup of ours runs. The next launch's
     * purge of `tmp/` removes the plaintext clip, and no object or row was ever written.
     */
    @Test
    fun a_clip_left_by_a_crash_is_removed_by_the_next_launches_purge() = runTest {
        val objects = vault()
        val recorder = recorder(objects, Job(), videoCamera(), mutableListOf())
        recorder.start()
        assertTrue(filesUnder(Path(root, "tmp")).isNotEmpty(), "the clip is on disk when the process dies")

        // Next launch: the bootstrap's purge, and nothing of ours.
        assertIs<Outcome.Ok<Unit>>(VaultStorage(root).purgeTmp())

        assertEquals(0L, objects.observeItemCount(ItemFilter.All).first(), "no row")
        assertTrue(filesUnder(root).isEmpty(), "no object and no clip: ${filesUnder(root)}")
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
