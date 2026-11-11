package dev.ehsanfahad.darkchamber.core.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import dev.ehsanfahad.darkchamber.core.model.Outcome
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Deleting an item must remove its wrapped key from the **files** the catalog lives in, not only from
 * the table — a crypto-shred is only as good as the bytes left on storage. These tests read the
 * database file and its journal/WAL directly.
 *
 * A file-backed JDBC driver stands in for the platform one, set up by [CatalogPragmas] exactly as the
 * platform `DriverFactory`s set theirs up. Both journal modes are covered: Android's framework runs a
 * rollback journal, iOS's native driver runs WAL.
 */
class SecureDeleteTest {
    private val dir: File = Files.createTempDirectory("secure-delete").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // 40 bytes nothing else in the catalog could contain by accident.
    private val wrappedKey = "WRAPPED-DEK-MARKER-0123456789-abcdefghij".encodeToByteArray()

    // For a file URL SQLDelight's JDBC driver opens a fresh connection per statement outside a
    // transaction, so a pragma executed once would be lost with its connection. The SQLite JDBC
    // driver applies pragmas given as connection properties to every connection it opens — the
    // per-connection hook the platform drivers' factories use.
    private val url = "jdbc:sqlite:${File(dir, "catalog.db").absolutePath}"

    private fun properties(wal: Boolean) = Properties().apply {
        if (wal) setProperty("journal_mode", "WAL")
        CatalogPragmas.onEveryConnection.forEach { setProperty(it.name, it.value) }
    }

    // The platform drivers keep their connection open for as long as the vault is unlocked.
    // SQLDelight's `JdbcSqliteDriver` does not for a file URL — it opens one per statement and closes
    // it after — and a connection that closes when nothing else holds a lock checkpoints a WAL and
    // deletes it, which hides exactly what the WAL case is about (found the hard way: with it, the
    // WAL never existed). So this driver keeps one connection for its whole life, as the app's do.
    private class OneConnectionDriver(private val connection: Connection) : JdbcDriver() {
        override fun getConnection(): Connection = connection
        override fun closeConnection(connection: Connection) = Unit
        override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit
        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit
        override fun notifyListeners(vararg queryKeys: String) = Unit
        override fun close() = connection.close()
    }

    private fun openDriver(wal: Boolean): SqlDriver =
        OneConnectionDriver(DriverManager.getConnection(url, properties(wal)))

    private fun store(driver: SqlDriver): CatalogStore =
        when (val opened = CatalogStore.create(driver)) {
            is Outcome.Ok -> opened.value
            is Outcome.Err -> fail("catalog did not open: ${opened.error}")
        }

    private fun item(id: Byte) = Items(
        id = byteArrayOf(id),
        album_id = null,
        kind = 0,
        size = 4096L,
        created_at = 1_700_000_000_000L,
        enc_meta = byteArrayOf(0x7F, 0x00, 0x11),
        dek_wrapped = wrappedKey.copyOf(),
    )

    // Every file the catalog has on disk right now: the database and any journal or WAL beside it.
    private fun catalogBytes(): List<Pair<String, ByteArray>> =
        dir.listFiles().orEmpty().filter { it.name.startsWith("catalog.db") }.map { it.name to it.readBytes() }

    private fun filesHoldingTheKey(): List<String> =
        catalogBytes().filter { (_, bytes) -> bytes.contains(wrappedKey) }.map { it.first }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    @Test
    fun a_deleted_item_leaves_no_wrapped_key_in_a_rollback_journal_catalog() {
        val driver = openDriver(wal = false)
        val catalog = store(driver)
        catalog.upsertItem(item(1))
        // The control: the scan finds the key while the row exists, so an empty result below means
        // the bytes are gone rather than that the scan looked in the wrong place.
        assertTrue(filesHoldingTheKey().isNotEmpty(), "control: the key should be on disk before the delete")

        catalog.deleteItem(byteArrayOf(1))

        assertTrue(filesHoldingTheKey().isEmpty(), "wrapped key still on disk in ${filesHoldingTheKey()}")
        driver.close()
    }

    @Test
    fun a_deleted_item_leaves_no_wrapped_key_in_a_wal_catalog_while_it_stays_open() {
        // WAL keeps every frame a transaction wrote — including the insert's, key and all — until a
        // checkpoint moves the pages into the database and the log is truncated. secure_delete zeroes
        // the delete's own frame; it cannot reach back into the insert's.
        val driver = openDriver(wal = true)
        val catalog = store(driver)
        catalog.upsertItem(item(1))
        assertTrue(filesHoldingTheKey() == listOf("catalog.db-wal"), "control: the key should be in the WAL, was in ${filesHoldingTheKey()}")

        catalog.deleteItem(byteArrayOf(1))

        assertTrue(filesHoldingTheKey().isEmpty(), "wrapped key still on disk in ${filesHoldingTheKey()}")
        driver.close()
    }

    @Test
    fun deleting_many_items_leaves_no_wrapped_key_in_a_wal_catalog_while_it_stays_open() {
        // The batch delete's own path: one transaction, then the checkpoint once it has committed (a
        // checkpoint cannot run inside the transaction it would have to copy).
        val driver = openDriver(wal = true)
        val catalog = store(driver)
        catalog.upsertItem(item(1))
        catalog.upsertItem(item(2))
        assertTrue(filesHoldingTheKey() == listOf("catalog.db-wal"), "control: the key should be in the WAL, was in ${filesHoldingTheKey()}")

        catalog.deleteItems(listOf(byteArrayOf(1), byteArrayOf(2)))

        assertTrue(filesHoldingTheKey().isEmpty(), "wrapped key still on disk in ${filesHoldingTheKey()}")
        driver.close()
    }
}
