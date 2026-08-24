package dev.ehsanfahad.darkchamber.core.db

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CatalogStoreTest {
    private val driver = testSqlDriver()
    private val db = darkChamberDatabase(driver)

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun schema_creates_and_an_items_row_round_trips_with_byte_identical_blobs() {
        val id = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val encMeta = byteArrayOf(0x7F, 0x00, 0x11.toByte(), 0xFF.toByte())
        val dekWrapped = byteArrayOf(0x0A, 0x0B, 0x0C)

        db.itemsQueries.upsertItem(
            id = id,
            album_id = null,
            kind = 0,
            size = 4096L,
            created_at = 1_700_000_000_000L,
            enc_meta = encMeta,
            dek_wrapped = dekWrapped,
        )

        val row = db.itemsQueries.selectItemById(id).executeAsOne()
        assertContentEquals(id, row.id, "id round-trips")
        assertEquals(null, row.album_id, "unfiled album is NULL")
        assertEquals(0, row.kind)
        assertEquals(4096L, row.size)
        assertEquals(1_700_000_000_000L, row.created_at)
        assertContentEquals(encMeta, row.enc_meta, "enc_meta is byte-identical (opaque blob)")
        assertContentEquals(dekWrapped, row.dek_wrapped, "dek_wrapped is byte-identical (opaque blob)")
        assertEquals(1L, db.itemsQueries.countItems().executeAsOne())
    }
}
