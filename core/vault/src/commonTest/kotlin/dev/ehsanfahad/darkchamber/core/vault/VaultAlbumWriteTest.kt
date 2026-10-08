package dev.ehsanfahad.darkchamber.core.vault

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Creating and renaming albums.
 *
 * Two decisions are pinned here rather than only documented. **Duplicate names are allowed** — the
 * id is identity and the name is a label — so a create must not go looking for a clash, which would
 * mean opening every album's name and undoing the listing's whole cost argument. And a **rename
 * replaces the name only**: the catalog upsert is `INSERT OR REPLACE`, so a rename built from a
 * fresh row would silently reset the album's creation time and drop its key-variant flag.
 */
class VaultAlbumWriteTest {
    private val fx = AlbumFixture()

    @AfterTest
    fun cleanup() = fx.close()

    @Test
    fun a_created_album_appears_in_the_listing_under_its_name() = runTest {
        val id = (fx.objects.createAlbum("Receipts") as Outcome.Ok).value

        fx.objects.observeAlbums().test {
            val listed = awaitItem().single()

            assertEquals(Outcome.Ok("Receipts"), listed.name)
            assertTrue(listed.id.contentEquals(id), "the id the create returned is the id in the listing")
            assertEquals(0, listed.itemCount, "a new album is empty")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun a_created_album_gets_a_16_byte_id_from_the_same_source_object_ids_come_from() {
        val first = (fx.objects.createAlbum("One") as Outcome.Ok).value
        val second = (fx.objects.createAlbum("Two") as Outcome.Ok).value

        assertEquals(16, first.size, "128-bit id, the width object ids use")
        assertFalse(first.contentEquals(second), "two creates are two different albums")
    }

    @Test
    fun the_sealed_name_is_not_readable_in_the_row() {
        val id = (fx.objects.createAlbum("Receipts") as Outcome.Ok).value

        val row = (fx.catalog.getAlbum(id) as Outcome.Ok).value!!

        assertFalse(
            row.enc_name.decodeToString().contains("Receipts"),
            "an album name is a secret; the column must not spell it out",
        )
        assertEquals(0, row.kek_variant, "sealed under MK, with no per-album key — an album is not a lock")
    }

    /**
     * The decision in full: two albums may share a name and stay distinct, and the create does not
     * refuse, warn, or deduplicate. Showing the user what exists beats refusing their input.
     */
    @Test
    fun two_albums_may_share_a_name_and_remain_distinct_by_id() = runTest {
        val first = (fx.objects.createAlbum("Holiday") as Outcome.Ok).value
        val second = (fx.objects.createAlbum("Holiday") as Outcome.Ok).value

        assertFalse(first.contentEquals(second), "same label, different albums")

        fx.objects.observeAlbums().test {
            val listed = awaitItem()
            assertEquals(2, listed.size, "both are listed")
            assertEquals(
                listOf(Outcome.Ok("Holiday"), Outcome.Ok("Holiday")),
                listed.map { it.name },
                "and both under the name the user chose",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Filing an item into one of two same-named albums must put it in that one. The name is a label;
     * if anything resolved albums by name this is where it would show.
     */
    @Test
    fun same_named_albums_hold_their_own_items() = runTest {
        val first = (fx.objects.createAlbum("Holiday") as Outcome.Ok).value
        val second = (fx.objects.createAlbum("Holiday") as Outcome.Ok).value
        fx.importInto(second, "beach.txt")

        fx.objects.observeAlbums().test {
            val listed = awaitItem()
            assertEquals(0, listed.single { it.id.contentEquals(first) }.itemCount)
            assertEquals(1, listed.single { it.id.contentEquals(second) }.itemCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun a_rename_changes_the_name_in_the_listing() = runTest {
        val id = (fx.objects.createAlbum("Recipts") as Outcome.Ok).value

        assertIs<Outcome.Ok<Unit>>(fx.objects.renameAlbum(id, "Receipts"))

        fx.objects.observeAlbums().test {
            assertEquals(Outcome.Ok("Receipts"), awaitItem().single().name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The `INSERT OR REPLACE` trap. A rename that rebuilt the row would look correct in the name
     * assertion above and quietly move the album to the end of a date-ordered grid, as well as
     * discarding the flag a later per-album key would be recorded in.
     */
    @Test
    fun a_rename_preserves_the_creation_time_and_the_key_variant() {
        val id = fx.putAlbum(7, fx.sealed("Before"), createdAt = 12_345L)

        assertIs<Outcome.Ok<Unit>>(fx.objects.renameAlbum(id, "After"))

        val row = (fx.catalog.getAlbum(id) as Outcome.Ok).value!!
        assertEquals(12_345L, row.created_at, "the album was not re-created, only renamed")
        assertEquals(0, row.kek_variant)
        assertEquals(Outcome.Ok(AlbumMeta("After")), fx.session.openAlbumMeta(row.enc_name))
    }

    @Test
    fun a_rename_keeps_the_items_where_they_are() = runTest {
        val id = (fx.objects.createAlbum("Trip") as Outcome.Ok).value
        val item = fx.importInto(id, "photo.txt")

        assertIs<Outcome.Ok<Unit>>(fx.objects.renameAlbum(id, "Trip 2026"))

        fx.objects.observeAlbums().test {
            val listed = awaitItem().single()
            assertEquals(1, listed.itemCount)
            assertTrue(listed.coverItemId.contentEquals(item), "renaming is not moving")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A rename of something that is not there must fail rather than create it. Quietly making the
     * album the caller thought they were editing is the worse of the two outcomes: the user ends up
     * with two albums and no error.
     */
    @Test
    fun renaming_an_unknown_album_fails_and_creates_nothing() {
        val result = fx.objects.renameAlbum(byteArrayOf(0xFF.toByte()), "Ghost")

        assertIs<Outcome.Err>(result)
        assertIs<VaultError.Io>(result.error, "absent, the same way an unknown object id is absent")
        assertEquals(emptyList(), (fx.catalog.listAlbums() as Outcome.Ok).value, "nothing was created")
    }

    /**
     * A blank name is a caller bug, not a user error: deciding what to do about what the user typed
     * belongs to the use case, which can disable a button and say why. Reaching here blank means that
     * validation was skipped, and failing loudly is how that is found in a test instead of becoming
     * an album that cannot be told apart from one whose name will not decrypt.
     */
    @Test
    fun a_blank_or_whitespace_name_is_rejected_as_a_precondition() {
        assertFailsWith<IllegalArgumentException> { fx.objects.createAlbum("") }
        assertFailsWith<IllegalArgumentException> { fx.objects.createAlbum("   ") }
        assertFailsWith<IllegalArgumentException> { fx.objects.createAlbum("\t\n") }

        val id = (fx.objects.createAlbum("Fine") as Outcome.Ok).value
        assertFailsWith<IllegalArgumentException> { fx.objects.renameAlbum(id, " ") }

        assertEquals(
            Outcome.Ok(AlbumMeta("Fine")),
            fx.session.openAlbumMeta((fx.catalog.getAlbum(id) as Outcome.Ok).value!!.enc_name),
            "the rejected rename left the name alone",
        )
        assertEquals(1, (fx.catalog.listAlbums() as Outcome.Ok).value.size, "and no blank album was stored")
    }

    /** A name is stored as given: surrounding space is the use case's business, not this layer's. */
    @Test
    fun a_name_with_content_is_stored_verbatim() = runTest {
        val id = (fx.objects.createAlbum(" Trip to Köln ") as Outcome.Ok).value

        fx.objects.observeAlbums().test {
            assertEquals(Outcome.Ok(" Trip to Köln "), awaitItem().single().name)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            Outcome.Ok(AlbumMeta(" Trip to Köln ")),
            fx.session.openAlbumMeta((fx.catalog.getAlbum(id) as Outcome.Ok).value!!.enc_name),
            "nothing here trims — that would be a product decision taken in the wrong layer",
        )
    }

    /** Creating an album touches no item, which is worth stating since the two share a listing. */
    @Test
    fun creating_an_album_does_not_disturb_the_items() {
        val item = fx.importInto(null, "unfiled.txt")

        assertIs<Outcome.Ok<ByteArray>>(fx.objects.createAlbum("New"))

        val row = (fx.catalog.getItem(item) as Outcome.Ok).value!!
        assertEquals(null, row.album_id, "a new album does not adopt anything")
    }
}
