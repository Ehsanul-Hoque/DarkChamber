package dev.ehsanfahad.darkchamber.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.ehsanfahad.darkchamber.core.model.Outcome
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The production [DriverFactory] on iOS, over a scratch directory: the real native driver, in its
 * real journal mode (WAL), with its connections held open as the app holds them while unlocked.
 */
@OptIn(ExperimentalForeignApi::class)
class DriverFactoryIosTest {
    private val directory = NSTemporaryDirectory() + "driver-factory-" + NSUUID().UUIDString
    private val path = "$directory/catalog.db"

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(directory, null)
    }

    private val wrappedKey = "WRAPPED-DEK-MARKER-0123456789-abcdefghij".encodeToByteArray()

    private fun SqlDriver.pragma(sql: String): String? =
        executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            parameters = 0,
        ).value

    private fun store(driver: SqlDriver): CatalogStore =
        when (val opened = CatalogStore.create(driver)) {
            is Outcome.Ok -> opened.value
            is Outcome.Err -> fail("catalog did not open: ${opened.error}")
        }

    @Test
    fun every_connection_the_factory_opens_has_secure_delete_on() {
        val driver = DriverFactory(path).create()
        store(driver)
        // A plain query runs on one of the driver's reader connections…
        assertEquals("1", driver.pragma("PRAGMA secure_delete"))
        // …and one inside a transaction on the writer, which is the one that deletes.
        val onWriter = darkChamberDatabase(driver).transactionWithResult { driver.pragma("PRAGMA secure_delete") }
        assertEquals("1", onWriter)
        driver.close()
    }

    @Test
    fun a_deleted_item_leaves_no_wrapped_key_in_the_files_while_the_catalog_stays_open() {
        val driver = DriverFactory(path).create()
        val catalog = store(driver)
        assertEquals("wal", driver.pragma("PRAGMA journal_mode"), "this test is about the WAL the native driver runs")
        catalog.upsertItem(
            Items(
                id = byteArrayOf(1),
                album_id = null,
                kind = 0,
                size = 4096L,
                created_at = 1_700_000_000_000L,
                enc_meta = byteArrayOf(0x7F, 0x00, 0x11),
                dek_wrapped = wrappedKey.copyOf(),
            ),
        )
        // Control: the scan finds the key before the delete, so an empty answer after it means gone.
        assertTrue(filesHoldingTheKey().isNotEmpty(), "control: the key should be on disk before the delete")

        catalog.deleteItem(byteArrayOf(1))

        assertTrue(filesHoldingTheKey().isEmpty(), "wrapped key still on disk in ${filesHoldingTheKey()}")
        driver.close()
    }

    private fun filesHoldingTheKey(): List<String> =
        listOf("catalog.db", "catalog.db-wal", "catalog.db-shm", "catalog.db-journal")
            .filter { name -> bytesOf("$directory/$name")?.contains(wrappedKey) == true }

    private fun bytesOf(file: String): ByteArray? {
        val data = NSData.dataWithContentsOfFile(file) ?: return null
        val size = data.length.toInt()
        if (size == 0) return ByteArray(0)
        return ByteArray(size).also { bytes -> bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
}
