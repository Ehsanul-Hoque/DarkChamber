package dev.ehsanfahad.darkchamber.core.db

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogStoreTest {
    private val driver = testSqlDriver()
    private val store = CatalogStore.create(driver).unwrap()

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun item(
        id: Byte,
        albumId: ByteArray? = null,
        createdAt: Long = 1_700_000_000_000L,
        encMeta: ByteArray = byteArrayOf(0x7F, 0x00, 0x11, 0xFF.toByte()),
        dek: ByteArray = byteArrayOf(0x0A, 0x0B),
    ) = Items(
        id = byteArrayOf(id),
        album_id = albumId,
        kind = 0,
        size = 4096L,
        created_at = createdAt,
        enc_meta = encMeta,
        dek_wrapped = dek,
    )

    @Test
    fun create_returns_Ok_and_builds_the_schema() {
        assertEquals(0L, store.countItems().unwrap())
    }

    @Test
    fun create_is_idempotent_on_an_already_open_driver() {
        // Second open on the same driver must not re-run Schema.create.
        assertTrue(CatalogStore.create(driver) is Outcome.Ok, "reopen is Ok")
    }

    @Test
    fun upsert_then_get_round_trips_with_byte_identical_blobs() {
        val encMeta = byteArrayOf(0x01, 0x7F, 0x00, 0xFF.toByte(), 0x42)
        val dek = byteArrayOf(0x0C, 0x0D, 0x0E)
        store.upsertItem(item(1, encMeta = encMeta, dek = dek)).unwrap()

        val row = store.getItem(byteArrayOf(1)).unwrap()!!
        assertContentEquals(byteArrayOf(1), row.id)
        assertContentEquals(encMeta, row.enc_meta, "enc_meta is byte-identical (opaque blob)")
        assertContentEquals(dek, row.dek_wrapped, "dek_wrapped is byte-identical (opaque blob)")
        assertEquals(4096L, row.size)
        assertEquals(1L, store.countItems().unwrap())
    }

    @Test
    fun getItem_returns_null_for_an_absent_id() {
        assertNull(store.getItem(byteArrayOf(9)).unwrap())
    }

    @Test
    fun upsert_replaces_an_existing_row() {
        store.upsertItem(item(1, createdAt = 100L)).unwrap()
        store.upsertItem(item(1, createdAt = 200L)).unwrap()
        assertEquals(1L, store.countItems().unwrap())
        assertEquals(200L, store.getItem(byteArrayOf(1)).unwrap()!!.created_at)
    }

    @Test
    fun listItems_all_is_newest_first() {
        store.upsertItem(item(1, createdAt = 300L)).unwrap()
        store.upsertItem(item(2, createdAt = 100L)).unwrap()
        store.upsertItem(item(3, createdAt = 200L)).unwrap()

        // Newest first: the catalog feeds a gallery, and what a user just added is what they expect
        // to see without scrolling. Oldest-first buries every import at the bottom of the grid.
        val ids = store.listItems().unwrap().map { it.created_at }
        assertEquals(listOf(300L, 200L, 100L), ids)
    }

    /**
     * A batch import lands many files inside the same millisecond, so ties are the common case here
     * rather than a corner. With no tiebreaker SQLite may return them in a different order each time
     * it re-runs the query, and the gallery re-emits on every write — so tiles would reshuffle
     * between two imports, and the arrival animation would play on items that never moved.
     */
    @Test
    fun listItems_breaks_created_at_ties_the_same_way_every_time() {
        val sameMillisecond = 500L
        store.upsertItem(item(1, createdAt = sameMillisecond)).unwrap()
        store.upsertItem(item(3, createdAt = sameMillisecond)).unwrap()
        store.upsertItem(item(2, createdAt = sameMillisecond)).unwrap()
        store.upsertItem(item(4, createdAt = 900L)).unwrap()

        val order = store.listItems().unwrap().map { it.id.single() }
        assertEquals(
            listOf<Byte>(4, 3, 2, 1),
            order,
            "the newer row still leads; the tied rows fall back to a total order of their own",
        )
        assertEquals(order, store.listItems().unwrap().map { it.id.single() }, "and it does not drift")
    }

    @Test
    fun listItems_filters_by_album() {
        val albumA = byteArrayOf(0xA1.toByte())
        val albumB = byteArrayOf(0xB2.toByte())
        store.upsertItem(item(1, albumId = albumA, createdAt = 100L)).unwrap()
        store.upsertItem(item(2, albumId = albumB, createdAt = 200L)).unwrap()
        store.upsertItem(item(3, albumId = albumA, createdAt = 300L)).unwrap()

        val inA = store.listItems(ItemFilter.Album(albumA)).unwrap()
        assertEquals(2, inA.size)
        // Same order as the unfiltered listing — an album must not sort differently from the vault.
        assertEquals(listOf(300L, 100L), inA.map { it.created_at })
    }

    /**
     * The case the old nullable filter could not express at all: `album_id = ?` bound to NULL
     * matches nothing, and the parameter's own `null` already meant "everything", so there was no
     * value to pass that returned these rows. Anything imported without an album — which is every
     * import until the user files it — was reachable only through the full listing.
     */
    @Test
    fun listItems_unfiled_returns_only_items_with_no_album() {
        val album = byteArrayOf(0xA1.toByte())
        store.upsertItem(item(1, albumId = album, createdAt = 100L)).unwrap()
        store.upsertItem(item(2, albumId = null, createdAt = 200L)).unwrap()
        store.upsertItem(item(3, albumId = null, createdAt = 300L)).unwrap()

        val unfiled = store.listItems(ItemFilter.Unfiled).unwrap()
        assertEquals(listOf<Byte>(3, 2), unfiled.map { it.id.single() }, "the unfiled rows, newest first")
        assertEquals(3, store.listItems(ItemFilter.All).unwrap().size, "All still means the whole vault")
        assertEquals(
            listOf<Byte>(1),
            store.listItems(ItemFilter.Album(album)).unwrap().map { it.id.single() },
            "and an album filter is not the unfiled one",
        )
    }

    /**
     * Unfiled is a *filter*, so it narrows; the tri-state exists to distinguish it from All, and the
     * two are easy to collapse into each other by accident — a vault whose items are all unfiled
     * makes them identical, which is the state a fresh vault is in.
     */
    @Test
    fun listItems_unfiled_is_empty_when_everything_is_filed() {
        store.upsertItem(item(1, albumId = byteArrayOf(0xA1.toByte()))).unwrap()
        store.upsertItem(item(2, albumId = byteArrayOf(0xB2.toByte()))).unwrap()

        assertEquals(emptyList(), store.listItems(ItemFilter.Unfiled).unwrap(), "nothing is unfiled")
        assertEquals(2, store.listItems().unwrap().size, "though the vault is not empty")
    }

    /** The reactive path takes the same filter, not just the one-shot listing. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun observeItems_unfiled_reemits_when_an_item_is_filed() = runTest {
        val obsDriver = testSqlDriver()
        val obsStore = CatalogStore.create(obsDriver, UnconfinedTestDispatcher(testScheduler)).unwrap()
        obsStore.upsertItem(item(1, albumId = null, createdAt = 100L)).unwrap()

        obsStore.observeItems(ItemFilter.Unfiled).test {
            assertEquals(listOf<Byte>(1), awaitItem().map { it.id.single() }, "the unfiled item is listed")

            // Filing it is a write to items, so the listener fires — and the row leaves this listing
            // without being deleted.
            obsStore.upsertItem(item(1, albumId = byteArrayOf(0xA1.toByte()), createdAt = 100L)).unwrap()
            assertEquals(emptyList(), awaitItem(), "filing an item removes it from Unfiled")

            cancelAndIgnoreRemainingEvents()
        }
        obsDriver.close()
    }

    @Test
    fun updateItemAlbum_files_a_whole_set_at_once() {
        val albumA = byteArrayOf(0xA1.toByte())
        store.upsertAlbum(album(0xA1.toByte())).unwrap()
        store.upsertItem(item(1, createdAt = 100L)).unwrap()
        store.upsertItem(item(2, createdAt = 200L)).unwrap()
        store.upsertItem(item(3, createdAt = 300L)).unwrap()

        store.updateItemAlbum(listOf(byteArrayOf(1), byteArrayOf(3)), albumA).unwrap()

        assertEquals(
            listOf<Byte>(3, 1),
            store.listItems(ItemFilter.Album(albumA)).unwrap().map { it.id.single() },
            "both named items are filed, in the listing's own order",
        )
        assertEquals(
            listOf<Byte>(2),
            store.listItems(ItemFilter.Unfiled).unwrap().map { it.id.single() },
            "and the item nobody named stayed where it was",
        )
    }

    /** The other direction of the same op: a `null` destination unfiles, rather than meaning "no change". */
    @Test
    fun updateItemAlbum_with_a_null_destination_unfiles() {
        val albumA = byteArrayOf(0xA1.toByte())
        store.upsertAlbum(album(0xA1.toByte())).unwrap()
        store.upsertItem(item(1, albumId = albumA)).unwrap()

        store.updateItemAlbum(listOf(byteArrayOf(1)), albumId = null).unwrap()

        assertEquals(1, store.listItems(ItemFilter.Unfiled).unwrap().size, "the item is unfiled")
        assertEquals(emptyList(), store.listItems(ItemFilter.Album(albumA)).unwrap(), "and out of the album")
    }

    /**
     * The schema declares no foreign key on `album_id`, so SQLite accepts a destination that does
     * not exist — and the result is an item no album surface can reach: it is in no listed album,
     * and it is not unfiled either, because its `album_id` is not NULL. The store enforces what the
     * schema does not.
     */
    @Test
    fun updateItemAlbum_rejects_a_destination_that_does_not_exist() {
        store.upsertItem(item(1)).unwrap()

        val result = store.updateItemAlbum(listOf(byteArrayOf(1)), byteArrayOf(0xFF.toByte()))

        assertTrue(result is Outcome.Err, "an unknown destination is an error, not a silent write")
        assertTrue(result.error is VaultError.Db, "mapped to VaultError.Db")
        assertEquals(
            1,
            store.listItems(ItemFilter.Unfiled).unwrap().size,
            "and the item is untouched — the transaction rolled back",
        )
    }

    /**
     * A rejected destination must take the *whole* set with it. Half a move is worse than none: the
     * user sees some of their selection in the album and has no way to tell which part failed.
     */
    @Test
    fun updateItemAlbum_rolls_the_whole_set_back_when_the_destination_is_missing() {
        store.upsertItem(item(1)).unwrap()
        store.upsertItem(item(2)).unwrap()

        assertTrue(
            store.updateItemAlbum(listOf(byteArrayOf(1), byteArrayOf(2)), byteArrayOf(0xFF.toByte())) is Outcome.Err,
        )
        assertEquals(2, store.listItems(ItemFilter.Unfiled).unwrap().size, "neither item moved")
    }

    /**
     * An empty set is `Ok`, and — the part worth pinning — it tells nobody anything happened.
     *
     * Running the statement would be harmless to the data: the generated query expands its ids into
     * `IN (?, ?, …)`, and SQLite accepts the resulting `IN ()` as matching nothing. What it would not
     * be harmless to is the listings, which get notified that `items` changed and re-read themselves
     * — in the gallery, re-decrypting every row's metadata to arrive back at what it already had. An
     * empty selection is an ordinary thing for a UI to send.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun updateItemAlbum_with_no_ids_changes_nothing_and_notifies_nobody() = runTest {
        val obsDriver = testSqlDriver()
        val obsStore = CatalogStore.create(obsDriver, UnconfinedTestDispatcher(testScheduler)).unwrap()
        obsStore.upsertAlbum(album(0xA1.toByte())).unwrap()
        obsStore.upsertItem(item(1)).unwrap()

        obsStore.observeItems().test {
            assertEquals(1, awaitItem().size, "the listing starts with the one item")

            assertTrue(obsStore.updateItemAlbum(emptyList(), byteArrayOf(0xA1.toByte())) is Outcome.Ok)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, obsStore.listItems(ItemFilter.Unfiled).unwrap().size, "and nothing was filed")
        obsDriver.close()
    }

    /**
     * The row count is how a caller distinguishes a clean batch from one whose ids have been
     * shredded underneath it. Without it, assigning five items and assigning five ids of which two
     * are gone are indistinguishable successes.
     */
    @Test
    fun updateItemAlbum_reports_how_many_rows_it_touched() {
        val albumA = byteArrayOf(0xA1.toByte())
        store.upsertAlbum(album(0xA1.toByte())).unwrap()
        store.upsertItem(item(1)).unwrap()
        store.upsertItem(item(2)).unwrap()

        assertEquals(
            2L,
            store.updateItemAlbum(listOf(byteArrayOf(1), byteArrayOf(2)), albumA).unwrap(),
            "both rows moved",
        )
        assertEquals(
            1L,
            store.updateItemAlbum(listOf(byteArrayOf(1), byteArrayOf(9)), null).unwrap(),
            "one id was not there, and the count says so",
        )
        assertEquals(0L, store.updateItemAlbum(emptyList(), albumA).unwrap(), "nothing asked, nothing touched")
    }

    /**
     * An item id that is not in the catalog is **not** an error, unlike a missing destination: an
     * item shredded between being selected and being assigned is an ordinary race, and its absence
     * from the album is already what the caller wanted.
     */
    @Test
    fun updateItemAlbum_ignores_item_ids_that_are_not_there() {
        val albumA = byteArrayOf(0xA1.toByte())
        store.upsertAlbum(album(0xA1.toByte())).unwrap()
        store.upsertItem(item(1)).unwrap()

        store.updateItemAlbum(listOf(byteArrayOf(1), byteArrayOf(9)), albumA).unwrap()

        assertEquals(
            listOf<Byte>(1),
            store.listItems(ItemFilter.Album(albumA)).unwrap().map { it.id.single() },
            "the real item filed; the ghost was skipped",
        )
        assertEquals(1L, store.countItems().unwrap(), "and no row was invented for it")
    }

    /**
     * What an album delete does to its contents. The items survive and become unfiled — deleting
     * their rows would destroy the wrapped DEKs inside them, which is the only way of reading those
     * files, and there is no undoing that.
     */
    @Test
    fun clearAlbumOnItems_unfiles_the_contents_without_destroying_them() {
        val albumA = byteArrayOf(0xA1.toByte())
        val albumB = byteArrayOf(0xB2.toByte())
        store.upsertItem(item(1, albumId = albumA, createdAt = 100L)).unwrap()
        store.upsertItem(item(2, albumId = albumA, createdAt = 200L)).unwrap()
        store.upsertItem(item(3, albumId = albumB, createdAt = 300L)).unwrap()

        store.clearAlbumOnItems(albumA).unwrap()

        assertEquals(3L, store.countItems().unwrap(), "every item still exists")
        assertEquals(
            listOf<Byte>(2, 1),
            store.listItems(ItemFilter.Unfiled).unwrap().map { it.id.single() },
            "the album's contents are unfiled",
        )
        assertEquals(
            listOf<Byte>(3),
            store.listItems(ItemFilter.Album(albumB)).unwrap().map { it.id.single() },
            "and another album is untouched",
        )
        assertContentEquals(
            byteArrayOf(0x0A, 0x0B),
            store.getItem(byteArrayOf(1)).unwrap()!!.dek_wrapped,
            "including the wrapped key that makes the file readable",
        )
    }

    /** Clearing an empty album, or one that never existed, is a no-op — the ordinary delete case. */
    @Test
    fun clearAlbumOnItems_is_a_no_op_when_nothing_is_in_the_album() {
        store.upsertItem(item(1, albumId = byteArrayOf(0xB2.toByte()))).unwrap()

        assertTrue(store.clearAlbumOnItems(byteArrayOf(0xA1.toByte())) is Outcome.Ok, "an empty album clears cleanly")
        assertTrue(store.clearAlbumOnItems(byteArrayOf(0xFF.toByte())) is Outcome.Ok, "so does an unknown one")
        assertEquals(
            listOf<Byte>(1),
            store.listItems(ItemFilter.Album(byteArrayOf(0xB2.toByte()))).unwrap().map { it.id.single() },
            "and the clear did not reach past its own album",
        )
    }

    /**
     * Both writes have to reach the listings, and the bulk one has to reach them **once**. A move of
     * fifty items emitting fifty times would re-decrypt every row's metadata fifty times over in the
     * gallery above it.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun the_album_writes_notify_the_listings_once_each() = runTest {
        val obsDriver = testSqlDriver()
        val obsStore = CatalogStore.create(obsDriver, UnconfinedTestDispatcher(testScheduler)).unwrap()
        val albumA = byteArrayOf(0xA1.toByte())
        obsStore.upsertAlbum(album(0xA1.toByte())).unwrap()
        obsStore.upsertItem(item(1, createdAt = 100L)).unwrap()
        obsStore.upsertItem(item(2, createdAt = 200L)).unwrap()

        obsStore.observeItems(ItemFilter.Album(albumA)).test {
            assertEquals(emptyList(), awaitItem(), "the album starts empty")

            obsStore.updateItemAlbum(listOf(byteArrayOf(1), byteArrayOf(2)), albumA).unwrap()
            assertEquals(listOf<Byte>(2, 1), awaitItem().map { it.id.single() }, "one emission for the whole set")
            expectNoEvents()

            obsStore.clearAlbumOnItems(albumA).unwrap()
            assertEquals(emptyList(), awaitItem(), "and the clear re-emits too")

            cancelAndIgnoreRemainingEvents()
        }
        obsDriver.close()
    }

    @Test
    fun deleteItem_removes_and_is_idempotent() {
        store.upsertItem(item(1)).unwrap()
        store.deleteItem(byteArrayOf(1)).unwrap()
        assertEquals(0L, store.countItems().unwrap())
        // Absent id is Ok, not a throw.
        assertTrue(store.deleteItem(byteArrayOf(1)) is Outcome.Ok)
    }

    private fun album(
        id: Byte,
        kekVariant: Int = 0,
        createdAt: Long = 1_700_000_000_000L,
        encName: ByteArray = byteArrayOf(0x20, 0x21, 0x22),
    ) = Albums(
        id = byteArrayOf(id),
        kek_variant = kekVariant,
        enc_name = encName,
        created_at = createdAt,
    )

    @Test
    fun album_upsert_then_get_round_trips_with_blob_and_adapter_fidelity() {
        val encName = byteArrayOf(0x00, 0x7F, 0xFF.toByte(), 0x40)
        store.upsertAlbum(album(1, kekVariant = 3, encName = encName)).unwrap()

        val row = store.getAlbum(byteArrayOf(1)).unwrap()!!
        assertContentEquals(byteArrayOf(1), row.id)
        assertContentEquals(encName, row.enc_name, "enc_name is byte-identical (opaque blob)")
        assertEquals(3, row.kek_variant, "kek_variant round-trips through the Int adapter")
    }

    @Test
    fun listAlbums_is_ordered_by_created_at_and_getAlbum_absent_is_null() {
        store.upsertAlbum(album(1, createdAt = 300L)).unwrap()
        store.upsertAlbum(album(2, createdAt = 100L)).unwrap()
        assertEquals(listOf(100L, 300L), store.listAlbums().unwrap().map { it.created_at })
        assertNull(store.getAlbum(byteArrayOf(9)).unwrap())
    }

    @Test
    fun deleteAlbum_removes_and_is_idempotent() {
        store.upsertAlbum(album(1)).unwrap()
        store.deleteAlbum(byteArrayOf(1)).unwrap()
        assertEquals(0, store.listAlbums().unwrap().size)
        assertTrue(store.deleteAlbum(byteArrayOf(1)) is Outcome.Ok)
    }

    @Test
    fun listAudit_returns_the_most_recent_N_ordered_by_timestamp_desc() {
        for (i in 1..5) {
            store.appendAudit(
                Audit_log(
                    id = byteArrayOf(i.toByte()),
                    timestamp = i.toLong() * 100L,
                    enc_payload = byteArrayOf(i.toByte(), 0x7F),
                ),
            ).unwrap()
        }
        val recent = store.listAudit(limit = 3).unwrap()
        assertEquals(listOf(500L, 400L, 300L), recent.map { it.timestamp })
        assertContentEquals(byteArrayOf(5, 0x7F), recent.first().enc_payload, "enc_payload is opaque + intact")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun observeItems_emits_initial_then_reemits_on_write_and_delete_ordered() = runTest {
        val obsDriver = testSqlDriver()
        val obsStore = CatalogStore.create(obsDriver, UnconfinedTestDispatcher(testScheduler)).unwrap()
        obsStore.observeItems().test {
            assertEquals(emptyList(), awaitItem(), "initial list is empty")

            obsStore.upsertItem(item(1, createdAt = 100L)).unwrap()
            assertEquals(listOf(100L), awaitItem().map { it.created_at }, "re-emits after insert")

            obsStore.upsertItem(item(2, createdAt = 50L)).unwrap()
            assertEquals(listOf(100L, 50L), awaitItem().map { it.created_at }, "newest first")

            obsStore.deleteItem(byteArrayOf(1)).unwrap()
            assertEquals(listOf(50L), awaitItem().map { it.created_at }, "re-emits after delete")

            cancelAndIgnoreRemainingEvents()
        }
        obsDriver.close()
    }

    /**
     * The album grid's liveness depends on a listener firing for a table the album rows do not live
     * in.
     *
     * Every assertion here writes to **items only** — no album row is touched after the first one is
     * created — so each re-emission proves the listener covers `items` as well as `albums`. If it
     * does not, the grid shows a count and a cover that were right when the screen opened and drift
     * silently from then on, which is worse than showing nothing because a number looks
     * authoritative.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun observeAlbums_reemits_when_only_the_items_table_changes() = runTest {
        val obsDriver = testSqlDriver()
        val obsStore = CatalogStore.create(obsDriver, UnconfinedTestDispatcher(testScheduler)).unwrap()
        obsStore.upsertAlbum(album(1)).unwrap()
        obsStore.upsertAlbum(album(2)).unwrap()

        obsStore.observeAlbums().test {
            val initial = awaitItem()
            assertEquals(listOf(0L, 0L), initial.map { it.item_count }, "both albums start empty")
            assertNull(initial.first().cover_item_id, "an empty album has no cover")

            // Insert into album 1.
            obsStore.upsertItem(item(10, albumId = byteArrayOf(1), createdAt = 100L)).unwrap()
            val afterInsert = awaitItem()
            assertEquals(listOf(1L, 0L), afterInsert.map { it.item_count }, "an insert into items re-emits")
            assertContentEquals(byteArrayOf(10), afterInsert.first().cover_item_id, "newest item becomes the cover")

            // A newer item takes the cover without changing which album anything is in.
            obsStore.upsertItem(item(11, albumId = byteArrayOf(1), createdAt = 200L)).unwrap()
            val afterNewer = awaitItem()
            assertContentEquals(byteArrayOf(11), afterNewer.first().cover_item_id, "the cover follows the newest item")

            // The case that matters: move an item between albums. The row count across the vault is
            // unchanged, so anything deduplicating on a total would miss this entirely — and moving
            // items between albums is the single most ordinary thing this screen has to reflect.
            obsStore.upsertItem(item(11, albumId = byteArrayOf(2), createdAt = 200L)).unwrap()
            val afterMove = awaitItem()
            assertEquals(listOf(1L, 1L), afterMove.map { it.item_count }, "a move re-emits both counts")
            assertContentEquals(byteArrayOf(10), afterMove.first().cover_item_id, "the cover falls back to what is left")
            assertContentEquals(byteArrayOf(11), afterMove.last().cover_item_id, "and the destination gains one")

            // Deleting the last item empties the album rather than dropping it from the listing.
            obsStore.deleteItem(byteArrayOf(11)).unwrap()
            val afterDelete = awaitItem()
            assertEquals(listOf(1L, 0L), afterDelete.map { it.item_count }, "a delete from items re-emits")
            assertNull(afterDelete.last().cover_item_id, "an emptied album keeps its row and loses its cover")

            cancelAndIgnoreRemainingEvents()
        }
        obsDriver.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun observeItemCount_all_emits_initial_then_reemits_on_insert_and_delete() = runTest {
        val obsDriver = testSqlDriver()
        val obsStore = CatalogStore.create(obsDriver, UnconfinedTestDispatcher(testScheduler)).unwrap()
        obsStore.observeItemCount(ItemFilter.All).test {
            assertEquals(0L, awaitItem(), "an empty vault counts zero")

            obsStore.upsertItem(item(1)).unwrap()
            assertEquals(1L, awaitItem(), "re-emits after insert")

            obsStore.upsertItem(item(2, albumId = byteArrayOf(0xA1.toByte()))).unwrap()
            assertEquals(2L, awaitItem(), "a filed item still counts towards the whole vault")

            obsStore.deleteItem(byteArrayOf(1)).unwrap()
            assertEquals(1L, awaitItem(), "re-emits after delete")

            cancelAndIgnoreRemainingEvents()
        }
        obsDriver.close()
    }

    /**
     * Filing an item changes no row count across the vault, so this is the case a total would miss:
     * the unfiled count must fall when an unfiled item moves into an album, and rise when it moves
     * back.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun observeItemCount_unfiled_counts_only_items_with_no_album_and_follows_a_move() = runTest {
        val obsDriver = testSqlDriver()
        val obsStore = CatalogStore.create(obsDriver, UnconfinedTestDispatcher(testScheduler)).unwrap()
        val albumId = byteArrayOf(0xA1.toByte())
        obsStore.upsertAlbum(album(0xA1.toByte())).unwrap()
        obsStore.upsertItem(item(1)).unwrap()
        obsStore.upsertItem(item(2)).unwrap()
        obsStore.upsertItem(item(3, albumId = albumId)).unwrap()

        obsStore.observeItemCount(ItemFilter.Unfiled).test {
            assertEquals(2L, awaitItem(), "the filed item is not counted")

            obsStore.updateItemAlbum(listOf(byteArrayOf(1)), albumId).unwrap()
            assertEquals(1L, awaitItem(), "filing an unfiled item lowers the count")

            obsStore.updateItemAlbum(listOf(byteArrayOf(3)), null).unwrap()
            assertEquals(2L, awaitItem(), "unfiling one raises it")

            cancelAndIgnoreRemainingEvents()
        }
        obsDriver.close()
    }

    /**
     * The filter is handed **its own copy** of the album id, as production's would be: an id decoded
     * out of SQLite is never the same array instance as one held by a caller, so a fixture sharing one
     * array would let an identity comparison pass for content.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun observeItemCount_album_counts_only_that_album_and_follows_a_move_between_albums() = runTest {
        val obsDriver = testSqlDriver()
        val obsStore = CatalogStore.create(obsDriver, UnconfinedTestDispatcher(testScheduler)).unwrap()
        obsStore.upsertAlbum(album(1)).unwrap()
        obsStore.upsertAlbum(album(2)).unwrap()
        obsStore.upsertItem(item(10, albumId = byteArrayOf(1))).unwrap()
        obsStore.upsertItem(item(11, albumId = byteArrayOf(1))).unwrap()
        obsStore.upsertItem(item(12, albumId = byteArrayOf(2))).unwrap()
        obsStore.upsertItem(item(13)).unwrap()

        obsStore.observeItemCount(ItemFilter.Album(byteArrayOf(1))).test {
            assertEquals(2L, awaitItem(), "neither the other album's item nor the unfiled one is counted")

            obsStore.updateItemAlbum(listOf(byteArrayOf(11)), byteArrayOf(2)).unwrap()
            assertEquals(1L, awaitItem(), "moving an item out to another album lowers the count")

            obsStore.upsertItem(item(14, albumId = byteArrayOf(1))).unwrap()
            assertEquals(2L, awaitItem(), "an item created into the album raises it")

            cancelAndIgnoreRemainingEvents()
        }
        obsDriver.close()
    }

    /**
     * A broken catalog must not read as an empty one. A count of zero is a claim about the vault
     * ("nothing here"), so a driver failure has to reach the collector as a failure.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun observeItemCount_surfaces_a_driver_failure_in_the_flow_rather_than_a_zero() = runTest {
        val obsDriver = testSqlDriver()
        val obsStore = CatalogStore.create(obsDriver, UnconfinedTestDispatcher(testScheduler)).unwrap()
        obsDriver.execute(identifier = null, sql = "DROP TABLE items", parameters = 0)

        obsStore.observeItemCount(ItemFilter.Unfiled).test {
            awaitError()
        }
        obsDriver.close()
    }

    @Test
    fun create_fails_closed_on_a_newer_catalog_version() {
        val newer = testSqlDriver()
        val future = DarkChamberDatabase.Schema.version + 1L
        newer.execute(identifier = null, sql = "PRAGMA user_version = $future", parameters = 0)
        val result = CatalogStore.create(newer)
        assertTrue(result is Outcome.Err, "a newer-than-supported catalog fails closed")
        assertTrue(result.error is VaultError.Db, "mapped to VaultError.Db")
        newer.close()
    }

    @Test
    fun a_driver_failure_surfaces_Err_Db_not_a_throw() {
        // Pull the table out from under the store; the next op must map to Err(Db), not throw.
        driver.execute(identifier = null, sql = "DROP TABLE items", parameters = 0)
        val result = store.countItems()
        assertTrue(result is Outcome.Err, "op returns Err, not a throw")
        assertTrue(result.error is VaultError.Db, "mapped to VaultError.Db")
    }
}

private fun <T> Outcome<T>.unwrap(): T = (this as Outcome.Ok<T>).value
