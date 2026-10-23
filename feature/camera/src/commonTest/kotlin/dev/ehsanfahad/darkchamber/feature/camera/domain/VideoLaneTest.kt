package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.core.model.NotSavedKind
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.vault.ImportPhase
import dev.ehsanfahad.darkchamber.feature.camera.FakeCameraController
import dev.ehsanfahad.darkchamber.feature.camera.FakeCaptureVault
import dev.ehsanfahad.darkchamber.feature.camera.FakeClipFile
import dev.ehsanfahad.darkchamber.feature.camera.GateDispatcher
import dev.ehsanfahad.darkchamber.feature.camera.api.ClipFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The video save lane: one recorded clip at a time, encrypted from its file into the vault in the
 * session's scope, and the file deleted on every way out.
 *
 * Every dispatcher is the test's, or a gate the test opens, so nothing passes on elapsed time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoLaneTest {

    private class Harness(test: TestScope, ioContext: CoroutineContext? = null) {
        val vault = FakeCaptureVault()
        val sessionJob = Job()
        val reports = mutableListOf<Triple<NotSavedKind, NotSavedReason, Int>>()
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(dispatcher + sessionJob)
        val lane = VideoLane(
            vault = vault,
            clock = { 42L },
            scope = scope,
            ioContext = ioContext ?: dispatcher,
            notSaved = { kind, reason, count -> reports += Triple(kind, reason, count) },
            nameFor = { "Video at $it" },
        ).apply { screenOpen = true }
    }

    @Test
    fun a_clip_is_saved_as_a_video_filed_where_the_camera_was_opened_and_its_file_deleted() = runTest {
        val h = Harness(this)
        val bytes = ByteArray(5_000) { (it * 7).toByte() }
        val clip = FakeClipFile(bytes)
        h.vault.nextResult = { Outcome.Ok(byteArrayOf(7, 7)) }

        assertTrue(h.lane.save(clip, albumId = byteArrayOf(9)))
        testScheduler.advanceUntilIdle()

        val imported = h.vault.imported.single()
        assertEquals(VaultItemKind.FILE.code, imported.kind)
        assertEquals("video/mp4", imported.mime)
        assertEquals("Video at 42", imported.name, "named from the injected clock")
        assertContentEquals(byteArrayOf(9), imported.albumId)
        assertContentEquals(bytes, imported.bytes)
        assertTrue(imported.fileUnfiledIfMissing, "a clip is never lost to its album going")
        assertTrue(clip.deleted, "the plaintext file is gone once it is in the vault")
        assertEquals(1, h.lane.state.value.saved)
        assertContentEquals(byteArrayOf(7, 7), h.lane.state.value.lastSavedId, "the new item's id, for the tile")
        assertFalse(h.lane.state.value.busy)
        h.sessionJob.cancel()
    }

    @Test
    fun the_whole_vault_and_unfiled_save_a_clip_unfiled() = runTest {
        val h = Harness(this)

        h.lane.save(FakeClipFile(), albumId = null)
        testScheduler.advanceUntilIdle()

        assertNull(h.vault.imported.single().albumId)
        h.sessionJob.cancel()
    }

    /** The codec reads its payload twice; each pass must open the file afresh, never share a reader. */
    @Test
    fun every_pass_of_the_import_opens_the_file_afresh() = runTest {
        val h = Harness(this)
        val bytes = ByteArray(3_000) { it.toByte() }
        val clip = FakeClipFile(bytes)
        var secondPass: ByteArray? = null
        h.vault.duringImport = { open -> secondPass = open().buffered().readByteArray() }

        h.lane.save(clip, albumId = null)
        testScheduler.advanceUntilIdle()

        assertContentEquals(bytes, secondPass, "a pass opened before the main read reads it all")
        assertContentEquals(bytes, h.vault.imported.single().bytes, "and so does the next one")
        assertEquals(2, clip.opens, "one open per pass")
        h.sessionJob.cancel()
    }

    @Test
    fun a_failed_save_deletes_the_file_and_tells_the_open_camera() = runTest {
        val h = Harness(this)
        h.vault.nextResult = { Outcome.Err(VaultError.Io(IllegalStateException("write"))) }
        val clip = FakeClipFile()

        h.lane.save(clip, albumId = null)
        testScheduler.advanceUntilIdle()

        assertTrue(clip.deleted, "plaintext never waits in tmp/ after a failure")
        assertEquals(VideoNotice.SaveFailed, h.lane.state.value.notice)
        assertEquals(0, h.lane.state.value.saved)
        assertTrue(h.reports.isEmpty(), "the open camera shows it; the record is for when it has gone")
        h.sessionJob.cancel()
    }

    @Test
    fun a_full_disk_is_named_and_its_file_deleted_at_once() = runTest {
        val h = Harness(this)
        h.vault.nextResult = { Outcome.Err(VaultError.Storage.Full) }
        val clip = FakeClipFile()

        h.lane.save(clip, albumId = null)
        testScheduler.advanceUntilIdle()

        assertTrue(clip.deleted)
        assertEquals(VideoNotice.OutOfSpace, h.lane.state.value.notice)
        h.sessionJob.cancel()
    }

    /** No screen is left to say so, so the record does — and names space only when space was the cause. */
    @Test
    fun a_failure_after_the_camera_closed_goes_to_the_record_with_its_reason() = runTest {
        val h = Harness(this)
        h.lane.screenOpen = false
        val results = ArrayDeque(listOf<Outcome<ByteArray>>(Outcome.Err(VaultError.Storage.Full), Outcome.Err(VaultError.Io(IllegalStateException()))))
        h.vault.nextResult = { results.removeFirst() }

        h.lane.save(FakeClipFile(), albumId = null)
        testScheduler.advanceUntilIdle()
        h.lane.save(FakeClipFile(), albumId = null)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                Triple(NotSavedKind.Video, NotSavedReason.OutOfSpace, 1),
                Triple(NotSavedKind.Video, NotSavedReason.CouldNotSave, 1),
            ),
            h.reports,
        )
        assertNull(h.lane.state.value.notice, "and nothing waits to be shown on a screen that has gone")
        h.sessionJob.cancel()
    }

    @Test
    fun a_clip_whose_album_went_is_saved_unfiled_and_says_so_where_it_can_be_seen() = runTest {
        val open = Harness(this)
        open.vault.albumGoesMissing = true
        open.lane.save(FakeClipFile(), albumId = byteArrayOf(9))
        testScheduler.advanceUntilIdle()
        assertEquals(VideoNotice.SavedUnfiled, open.lane.state.value.notice)
        assertEquals(1, open.lane.state.value.saved, "it is saved, not lost")
        assertTrue(open.reports.isEmpty())
        open.sessionJob.cancel()

        val closed = Harness(this)
        closed.lane.screenOpen = false
        closed.vault.albumGoesMissing = true
        closed.lane.save(FakeClipFile(), albumId = byteArrayOf(9))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.SavedUnfiled, 1)), closed.reports)
        closed.sessionJob.cancel()
    }

    /**
     * One clip at a time: a second plaintext file would break the size limit's arithmetic, which
     * assumes no other encrypt is pending. A refused clip stays its caller's to delete.
     */
    @Test
    fun the_lane_is_busy_from_hand_over_until_saved_and_refuses_a_second_clip() = runTest {
        val worker = GateDispatcher()
        val h = Harness(this, ioContext = worker)
        val first = FakeClipFile()
        val second = FakeClipFile()

        assertTrue(h.lane.save(first, albumId = null))
        assertTrue(h.lane.state.value.busy, "busy from the moment it is handed over")
        testScheduler.advanceUntilIdle()
        assertFalse(h.lane.save(second, albumId = null), "a second clip is refused while one is held")
        assertFalse(second.deleted, "and left for its caller")

        worker.runAll()
        testScheduler.advanceUntilIdle()
        assertFalse(h.lane.state.value.busy)
        assertEquals(1, h.vault.imported.size, "only the first was saved")
        assertTrue(h.lane.save(second, albumId = null), "free again once it is saved")
        h.sessionJob.cancel()
    }

    @Test
    fun a_failed_save_frees_the_lane_too() = runTest {
        val h = Harness(this)
        h.vault.nextResult = { Outcome.Err(VaultError.Io(IllegalStateException())) }

        h.lane.save(FakeClipFile(), albumId = null)
        testScheduler.advanceUntilIdle()

        assertFalse(h.lane.state.value.busy)
        h.sessionJob.cancel()
    }

    /** Photos never wait behind a clip: the photo queue saves while the lane's encrypt is stuck. */
    @Test
    fun a_photo_saves_while_a_clip_is_stuck_mid_encrypt() = runTest {
        val stuck = GateDispatcher()
        val h = Harness(this, ioContext = stuck)
        val camera = FakeCameraController()
        val queue = CaptureQueue(
            camera = camera,
            save = SavePhoto(h.vault, clock = { 0L }, nameFor = { "p.jpg" }),
            albumId = null,
            scope = h.scope,
            ioContext = h.dispatcher,
            notSaved = { _, _, _ -> },
        )

        h.lane.save(FakeClipFile(), albumId = null)
        testScheduler.advanceUntilIdle()
        assertEquals(1, stuck.pending, "the clip's encrypt is held")
        queue.shoot()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("image/jpeg"), h.vault.imported.map { it.mime }, "the photo is in")
        assertTrue(h.lane.state.value.busy, "while the clip still waits")
        h.sessionJob.cancel()
    }

    /** The tile's ring: the encrypt reads the file twice, so its bytes are drawn against twice the size. */
    @Test
    fun progress_is_the_encrypts_bytes_against_twice_the_file_size() = runTest {
        val h = Harness(this)
        val clip = FakeClipFile(ByteArray(1_000))
        h.vault.progressReports = listOf(
            ImportPhase.ENCRYPTING to 500L,
            ImportPhase.ENCRYPTING to 1_000L,
            ImportPhase.ENCRYPTING to 2_000L,
        )
        val seen = mutableListOf<Float?>()
        h.vault.afterEachProgress = { seen += h.lane.state.value.progress }

        h.lane.save(clip, albumId = null)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf<Float?>(0.25f, 0.5f, 1f), seen)
        assertNull(h.lane.state.value.progress, "no ring once it is saved")
        h.sessionJob.cancel()
    }

    @Test
    fun with_no_size_to_measure_against_progress_stays_unknown() = runTest {
        val h = Harness(this)
        h.vault.progressReports = listOf(ImportPhase.ENCRYPTING to 500L)
        val seen = mutableListOf<Float?>()
        h.vault.afterEachProgress = { seen += h.lane.state.value.progress }
        val unmeasurable = object : ClipFile by FakeClipFile() {
            override fun size(): Long? = null
        }

        h.lane.save(unmeasurable, albumId = null)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf<Float?>(null), seen)
        h.sessionJob.cancel()
    }

    /**
     * The lock lands inside the encrypt. The import sees the session go and stops; the file is
     * deleted, and the clip is recorded as lost to the lock — wherever the camera was, since a lock
     * takes the screen with it.
     */
    @Test
    fun a_lock_mid_encrypt_deletes_the_file_and_records_a_video_lost_to_the_lock() = runTest {
        val h = Harness(this)
        val clip = FakeClipFile()
        h.vault.duringImport = { h.sessionJob.cancel() }
        h.vault.nextResult = { Outcome.Err(VaultError.Io(IllegalStateException("abandoned"))) }

        h.lane.save(clip, albumId = null)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(false), h.vault.activeAfterImports, "the import saw the lock")
        assertTrue(clip.deleted)
        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.Locked, 1)), h.reports)
        assertNull(h.lane.state.value.notice, "a lock is not a failure the screen shows")
    }

    /** The lock lands after the hand-over but before the encrypt has started on its own thread. */
    @Test
    fun a_lock_before_the_encrypt_starts_still_deletes_the_file_and_records_it() = runTest {
        val worker = GateDispatcher()
        val h = Harness(this, ioContext = worker)
        val clip = FakeClipFile()

        h.lane.save(clip, albumId = null)
        testScheduler.advanceUntilIdle()
        assertEquals(1, worker.pending, "the encrypt is waiting to start")
        h.sessionJob.cancel()
        worker.runAll()
        testScheduler.advanceUntilIdle()

        assertTrue(h.vault.imported.isEmpty(), "the encrypt never ran")
        assertTrue(clip.deleted)
        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.Locked, 1)), h.reports)
    }

    /** A clip handed over as the lock lands — the lane's scope already gone — is not left in tmp/. */
    @Test
    fun a_clip_handed_to_a_locked_lane_is_deleted_and_recorded() = runTest {
        val h = Harness(this)
        h.sessionJob.cancel()
        val clip = FakeClipFile()

        h.lane.save(clip, albumId = null)
        testScheduler.advanceUntilIdle()

        assertTrue(clip.deleted)
        assertTrue(h.vault.imported.isEmpty())
        assertEquals(listOf(Triple(NotSavedKind.Video, NotSavedReason.Locked, 1)), h.reports)
    }

    /**
     * The row committed, then the lock arrived before the lane heard back. The clip **is** in the
     * vault, so telling the user it was lost would be false.
     */
    @Test
    fun a_clip_saved_just_before_the_lock_is_not_recorded_as_lost() = runTest {
        val h = Harness(this)
        val clip = FakeClipFile()
        h.vault.nextResult = {
            h.sessionJob.cancel()
            Outcome.Ok(byteArrayOf(5))
        }

        h.lane.save(clip, albumId = null)
        testScheduler.advanceUntilIdle()

        assertTrue(clip.deleted)
        assertTrue(h.reports.isEmpty(), "nothing was lost: ${h.reports}")
    }

    @Test
    fun opening_the_camera_again_clears_a_notice_left_from_before() = runTest {
        val h = Harness(this)
        h.vault.nextResult = { Outcome.Err(VaultError.Io(IllegalStateException())) }
        h.lane.save(FakeClipFile(), albumId = null)
        testScheduler.advanceUntilIdle()
        assertEquals(VideoNotice.SaveFailed, h.lane.state.value.notice)

        h.lane.screenOpen = false
        h.lane.screenOpen = true

        assertNull(h.lane.state.value.notice)
        h.sessionJob.cancel()
    }
}
