package dev.ehsanfahad.darkchamber.core.vault

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Importing straight into an album.
 *
 * The filing happens as the row is written, so there is no window in which the item exists unfiled —
 * which matters because the gallery re-emits on that write and a two-step import would show the file
 * arriving in the wrong place first.
 *
 * The harder half is the destination going away. The schema declares no foreign key, so a row
 * naming a deleted album is accepted without complaint, and the item then sits where no album view
 * can reach it and the unfiled listing will not show it either — its `album_id` is not null. An
 * import is also long: minutes for a video, with the user free to delete the album while it runs.
 */
class VaultImportIntoAlbumTest {

    private fun AlbumFixture.import(
        albumId: ByteArray?,
        name: String = "photo.txt",
        onProgress: (ImportPhase, Long) -> Unit = { _, _ -> },
    ): Outcome<ByteArray> =
        objects.importObject(
            kind = VaultItemKind.FILE.code,
            name = name,
            mime = "text/plain",
            albumId = albumId,
            chunkSize = 64,
            onProgress = onProgress,
            openSource = { Buffer().apply { write(ByteArray(200) { 7 }) } as RawSource },
        )

    @Test
    fun an_import_with_an_album_lands_in_that_album() = runTest {
        val fx = AlbumFixture()
        val album = (fx.objects.createAlbum("Trip") as Outcome.Ok).value

        val id = (fx.import(album) as Outcome.Ok).value

        assertEquals(
            listOf(id.toList()),
            (fx.catalog.listItems(ItemFilter.Album(album)) as Outcome.Ok).value.map { it.id.toList() },
            "filed on arrival",
        )
        assertEquals(
            emptyList(),
            (fx.catalog.listItems(ItemFilter.Unfiled) as Outcome.Ok).value,
            "and never passed through unfiled on the way",
        )
        fx.close()
    }

    @Test
    fun the_imported_item_appears_under_that_albums_filter_and_in_its_count() = runTest {
        val fx = AlbumFixture()
        val album = (fx.objects.createAlbum("Trip") as Outcome.Ok).value

        fx.objects.observeItems(ItemFilter.Album(album)).test {
            assertEquals(emptyList(), awaitItem())

            val id = (fx.import(album) as Outcome.Ok).value

            val listed = awaitItem().single()
            assertTrue(listed.id.contentEquals(id))
            assertEquals("photo.txt", (listed.meta as Outcome.Ok).value.name, "with its metadata intact")
            cancelAndIgnoreRemainingEvents()
        }

        fx.objects.observeAlbums().test {
            assertEquals(1, awaitItem().single().itemCount, "the album's count includes it")
            cancelAndIgnoreRemainingEvents()
        }
        fx.close()
    }

    /** The default is unchanged behaviour: unfiled, which is what an import from the whole vault is. */
    @Test
    fun an_import_with_no_album_is_unfiled() = runTest {
        val fx = AlbumFixture()

        val id = (fx.import(albumId = null) as Outcome.Ok).value

        assertNull((fx.catalog.getItem(id) as Outcome.Ok).value!!.album_id)
        assertEquals(1, (fx.catalog.listItems(ItemFilter.Unfiled) as Outcome.Ok).value.size)
        fx.close()
    }

    /**
     * A destination that was never there fails before any bytes move, so a mistyped album does not
     * cost a full encrypt first.
     */
    @Test
    fun an_import_into_an_album_that_does_not_exist_fails_and_stores_nothing() = runTest {
        val fx = AlbumFixture()

        val result = fx.import(byteArrayOf(0xFF.toByte()))

        assertIs<Outcome.Err>(result)
        assertEquals(
            VaultError.Storage.DestinationMissing,
            result.error,
            "a missing album, told apart from a missing object without reading a message",
        )
        assertEquals(0L, (fx.catalog.countItems() as Outcome.Ok).value, "no row")
        assertEquals(
            emptyList(),
            (fx.catalog.listItems() as Outcome.Ok).value,
            "and nothing filed anywhere, not even unfiled as a consolation",
        )
        fx.close()
    }

    /**
     * The *first* check's whole reason to exist: the payload is never touched. The second check
     * would catch this destination anyway, so correctness is not what is being pinned here —
     * without this, a mistyped album costs a full encrypt of a video before the import reports a
     * problem that was knowable from the start.
     */
    @Test
    fun an_import_into_a_missing_album_never_opens_the_payload() = runTest {
        val fx = AlbumFixture()
        var opens = 0

        val result = fx.objects.importObject(
            kind = VaultItemKind.FILE.code,
            name = "big-video.mp4",
            mime = "video/mp4",
            albumId = byteArrayOf(0xFF.toByte()),
            chunkSize = 64,
            openSource = {
                opens++
                Buffer().apply { write(ByteArray(200) { 7 }) } as RawSource
            },
        )

        assertIs<Outcome.Err>(result)
        assertEquals(0, opens, "the destination is checked before a single byte is read")
        fx.close()
    }

    /**
     * **The window the second check exists for.** The album is deleted from inside the progress
     * callback — during the encrypt, after the destination was verified and before the row is
     * written — which is exactly what a user deleting an album during a long import does.
     *
     * Without the re-check the row lands naming an album that is gone: invisible in every album
     * view, invisible under Unfiled, and present only in the whole-vault listing.
     */
    @Test
    fun an_album_deleted_while_the_import_runs_fails_the_import_rather_than_stranding_the_item() = runTest {
        val fx = AlbumFixture()
        val album = (fx.objects.createAlbum("Doomed") as Outcome.Ok).value
        var deleted = false

        val result = fx.import(album) { phase, _ ->
            // Once, partway through the encrypt: the destination has already been checked by now.
            if (!deleted && phase == ImportPhase.ENCRYPTING) {
                deleted = true
                assertIs<Outcome.Ok<Unit>>(fx.objects.deleteAlbum(album))
            }
        }

        assertTrue(deleted, "the deletion really happened mid-import")
        assertIs<Outcome.Err>(result, "the import fails rather than filing into a hole")
        assertEquals(VaultError.Storage.DestinationMissing, result.error)
        assertEquals(0L, (fx.catalog.countItems() as Outcome.Ok).value, "no row was written")
        assertNoItemPointsAtAMissingAlbum(fx)
        fx.close()
    }

    /**
     * The same window, checked from the other side: a successful import is one whose destination was
     * still there, and nothing about the ordinary path writes a dangling reference.
     */
    @Test
    fun a_successful_import_leaves_no_dangling_album_reference() = runTest {
        val fx = AlbumFixture()
        val album = (fx.objects.createAlbum("Trip") as Outcome.Ok).value

        repeat(3) { assertIs<Outcome.Ok<ByteArray>>(fx.import(album, name = "file-$it.txt")) }

        assertNoItemPointsAtAMissingAlbum(fx)
        assertEquals(3, (fx.catalog.listItems(ItemFilter.Album(album)) as Outcome.Ok).value.size)
        fx.close()
    }

    // --- Opting in to unfiled when the album is gone ---

    private fun AlbumFixture.importFallingBack(
        albumId: ByteArray?,
        onFiledUnfiled: () -> Unit,
        onProgress: (ImportPhase, Long) -> Unit = { _, _ -> },
    ): Outcome<ByteArray> =
        objects.importObject(
            kind = VaultItemKind.FILE.code,
            name = "clip.mp4",
            mime = "video/mp4",
            albumId = albumId,
            chunkSize = 64,
            onProgress = onProgress,
            whenAlbumMissing = WhenAlbumMissing.FileUnfiled(onFiledUnfiled),
            openSource = { Buffer().apply { write(ByteArray(200) { 7 }) } as RawSource },
        )

    /** Opting in changes nothing while the album is there. */
    @Test
    fun with_the_fallback_an_album_that_is_there_is_still_where_the_item_goes() = runTest {
        val fx = AlbumFixture()
        val album = (fx.objects.createAlbum("Trip") as Outcome.Ok).value
        var fellBack = 0

        val id = (fx.importFallingBack(album, onFiledUnfiled = { fellBack++ }) as Outcome.Ok).value

        assertTrue((fx.catalog.getItem(id) as Outcome.Ok).value!!.album_id!!.contentEquals(album))
        assertEquals(0, fellBack, "nothing to report")
        fx.close()
    }

    @Test
    fun with_the_fallback_an_album_gone_before_the_import_files_it_unfiled() = runTest {
        val fx = AlbumFixture()
        var fellBack = 0

        val id = (fx.importFallingBack(byteArrayOf(0xFF.toByte()), onFiledUnfiled = { fellBack++ }) as Outcome.Ok).value

        assertNull((fx.catalog.getItem(id) as Outcome.Ok).value!!.album_id, "kept, unfiled")
        assertEquals(listOf(id.toList()), (fx.catalog.listItems(ItemFilter.Unfiled) as Outcome.Ok).value.map { it.id.toList() })
        assertEquals(1, fellBack, "and the caller is told, once")
        fx.close()
    }

    /**
     * The case the option exists for: a clip that took minutes to encrypt is not thrown away because
     * its album was deleted while it ran. The row is written unfiled — no second encrypt — and no
     * item is left naming an album that is gone.
     */
    @Test
    fun with_the_fallback_an_album_deleted_mid_import_files_it_unfiled_instead_of_failing() = runTest {
        val fx = AlbumFixture()
        val album = (fx.objects.createAlbum("Doomed") as Outcome.Ok).value
        var deleted = false
        var fellBack = 0
        var encrypted = 0L

        val result = fx.importFallingBack(album, onFiledUnfiled = { fellBack++ }) { phase, bytes ->
            if (phase == ImportPhase.ENCRYPTING) encrypted = bytes
            if (!deleted && phase == ImportPhase.ENCRYPTING) {
                deleted = true
                assertIs<Outcome.Ok<Unit>>(fx.objects.deleteAlbum(album))
            }
        }

        assertTrue(deleted, "the deletion really happened mid-import")
        val id = (result as Outcome.Ok).value
        assertNull((fx.catalog.getItem(id) as Outcome.Ok).value!!.album_id, "kept, unfiled")
        assertEquals(1, fellBack, "and the caller is told, once")
        assertEquals(400L, encrypted, "one encrypt: both passes of 200 bytes, never a second import")
        assertNoItemPointsAtAMissingAlbum(fx)
        fx.close()
    }

    /**
     * The fallback is for an album that is *gone*, never for a catalog that could not say. Filing
     * unfiled on a failed read would put the item somewhere the caller did not ask on the strength of
     * an answer nobody got.
     */
    @Test
    fun with_the_fallback_a_catalog_that_cannot_be_read_still_fails_the_import() = runTest {
        val driver = FailingSqlDriver(
            testSqlDriver(),
            failOn = { false },
            // The album lookup by id; SQLDelight expands its `*` into the column list.
            failQueryOn = { it.endsWith("FROM albums WHERE id = ?") },
        )
        val fx = AlbumFixture(driver = driver)
        var fellBack = 0

        val result = fx.importFallingBack(byteArrayOf(0xFF.toByte()), onFiledUnfiled = { fellBack++ })

        assertIs<Outcome.Err>(result)
        assertIs<VaultError.Db>(result.error, "the catalog's failure, not a missing album")
        assertTrue(driver.refusals > 0, "the read really was refused")
        assertEquals(0, fellBack, "never told it was filed unfiled")
        assertEquals(0L, (fx.catalog.countItems() as Outcome.Ok).value, "nothing stored")
        fx.close()
    }

    private fun assertNoItemPointsAtAMissingAlbum(fx: AlbumFixture) {
        val albumIds = (fx.catalog.listAlbums() as Outcome.Ok).value.map { it.id.toList() }
        val orphans = (fx.catalog.listItems() as Outcome.Ok).value
            .mapNotNull { it.album_id?.toList() }
            .filter { it !in albumIds }

        assertEquals(emptyList(), orphans, "no item may point at an album that does not exist")
    }
}
