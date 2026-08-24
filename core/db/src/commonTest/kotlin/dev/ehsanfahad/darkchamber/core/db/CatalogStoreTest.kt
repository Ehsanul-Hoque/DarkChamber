package dev.ehsanfahad.darkchamber.core.db

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
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
    fun listItems_all_is_ordered_by_created_at() {
        store.upsertItem(item(1, createdAt = 300L)).unwrap()
        store.upsertItem(item(2, createdAt = 100L)).unwrap()
        store.upsertItem(item(3, createdAt = 200L)).unwrap()

        val ids = store.listItems().unwrap().map { it.created_at }
        assertEquals(listOf(100L, 200L, 300L), ids)
    }

    @Test
    fun listItems_filters_by_album() {
        val albumA = byteArrayOf(0xA1.toByte())
        val albumB = byteArrayOf(0xB2.toByte())
        store.upsertItem(item(1, albumId = albumA, createdAt = 100L)).unwrap()
        store.upsertItem(item(2, albumId = albumB, createdAt = 200L)).unwrap()
        store.upsertItem(item(3, albumId = albumA, createdAt = 300L)).unwrap()

        val inA = store.listItems(albumA).unwrap()
        assertEquals(2, inA.size)
        assertEquals(listOf(100L, 300L), inA.map { it.created_at })
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
