package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Shredding a selection: the rows go first and together (that destroys the wrapped keys, and is the
 * shred), then each item's object and preview files, best-effort.
 */
class VaultShredManyTest {
    private val testDir = Path(SystemTemporaryDirectory, "vsm-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)

    private val session: VaultSession =
        VaultSession.createVault(engine, kdf, "correct horse battery".encodeToByteArray(), cheapKdf).session

    private val storage: VaultStorage by lazy {
        SystemFileSystem.createDirectories(testDir)
        VaultStorage(testDir)
    }

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun objects(catalog: CatalogStore = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value): VaultObjects =
        VaultObjects(
            session,
            storage,
            catalog,
            DchmCodec(engine),
            clock = { 42L },
            random = Random(7).let { rng -> { n -> rng.nextBytes(n) } },
            thumbnails = FakeThumbnailEncoder(FakeThumbnailEncoder.Mode.Encode),
        )

    private fun source(data: ByteArray): () -> RawSource = { Buffer().apply { write(data) } }

    // An image, so the import writes a preview as well: both files have to go.
    private fun import(vo: VaultObjects, n: Int): ByteArray =
        (vo.importObject(kind = 0, name = "p$n.jpg", mime = "image/jpeg", chunkSize = 64, openSource = source(ByteArray(120) { n.toByte() })) as Outcome.Ok).value

    private fun listed(vo: VaultObjects, catalog: CatalogStore): Int =
        (catalog.listItems(ItemFilter.All) as Outcome.Ok).value.size

    @Test
    fun shredding_a_selection_removes_every_row_object_and_preview_and_counts_them() {
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val vo = objects(catalog)
        val ids = (1..3).map { import(vo, it) }
        assertTrue(ids.all { storage.objectExists(it) && storage.thumbnailExists(it) }, "control: every file is there first")

        // Copies, never the arrays the imports returned: the gallery hands these back decoded.
        val result = vo.shredObjects(ids.map { it.copyOf() })

        assertEquals(Outcome.Ok(3L), result)
        assertEquals(0, listed(vo, catalog), "no row is left, and with it no wrapped key")
        assertTrue(ids.none { storage.objectExists(it) }, "no object is left behind")
        assertTrue(ids.none { storage.thumbnailExists(it) }, "and no preview")
    }

    @Test
    fun an_item_already_gone_is_skipped_and_not_counted() {
        val vo = objects()
        val kept = import(vo, 1)
        val alreadyGone = import(vo, 2)
        assertIs<Outcome.Ok<Unit>>(vo.shredObject(alreadyGone))

        assertEquals(Outcome.Ok(1L), vo.shredObjects(listOf(kept.copyOf(), alreadyGone.copyOf())))
        assertFalse(storage.objectExists(kept))
    }

    /**
     * The rows are the shred, so they go first: a catalog that refuses the delete must leave every
     * item whole — its row, its object, its preview, and its contents still readable. An order that
     * deleted files first would leave rows pointing at nothing.
     */
    @Test
    fun a_catalog_that_refuses_the_delete_shreds_nothing() {
        val driver = FailingSqlDriver(testSqlDriver(), failOn = { sql -> sql.startsWith("DELETE FROM items") })
        val catalog = (CatalogStore.create(driver) as Outcome.Ok).value
        val vo = objects(catalog)
        val ids = (1..2).map { import(vo, it) }

        val result = vo.shredObjects(ids.map { it.copyOf() })

        assertTrue(result is Outcome.Err && result.error is VaultError.Db, "a refused delete is Err(Db), was $result")
        assertEquals(1, driver.refusals, "control: the refusal is what this test exercised")
        assertEquals(2, listed(vo, catalog), "every row is still there")
        for ((n, id) in ids.withIndex()) {
            assertTrue(storage.objectExists(id) && storage.thumbnailExists(id), "item $n keeps its files")
            val out = Buffer()
            assertIs<Outcome.Ok<Unit>>(vo.openObject(id, out), "item $n still opens")
            assertEquals(120L, out.size)
        }
    }

    /**
     * A file that will not go is not an un-shred: with the row gone there is no DEK, and the
     * ciphertext is noise. So the shred is still `Ok`, every row still goes, and every other file is
     * still removed. The refusal is real here — the object's path is made a non-empty directory,
     * which no filesystem will delete — because a merely *missing* file deletes "successfully".
     */
    @Test
    fun an_object_file_that_cannot_be_deleted_does_not_undo_the_shred() {
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val vo = objects(catalog)
        val stuck = import(vo, 1)
        val other = import(vo, 2)
        val stuckPath = objectPathOf(stuck)
        SystemFileSystem.delete(stuckPath)
        SystemFileSystem.createDirectories(stuckPath)
        SystemFileSystem.sink(Path(stuckPath, "occupant")).close()
        assertTrue(storage.deleteObject(stuck.copyOf()) is Outcome.Err, "control: this object's delete really fails")

        assertEquals(Outcome.Ok(2L), vo.shredObjects(listOf(stuck.copyOf(), other.copyOf())))
        assertEquals(0, listed(vo, catalog), "both rows went, and that is the shred")
        assertFalse(storage.thumbnailExists(stuck), "the stuck item's preview was still removed")
        assertFalse(storage.objectExists(other) || storage.thumbnailExists(other), "and the next item's files too")
    }

    // The object store's file for [id]: the one file under objects/ named for it.
    private fun objectPathOf(id: ByteArray): Path {
        val hex = id.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        fun walk(dir: Path): List<Path> =
            SystemFileSystem.list(dir).flatMap { if (SystemFileSystem.metadataOrNull(it)?.isDirectory == true) walk(it) else listOf(it) }
        return walk(Path(testDir, "objects")).single { it.name.startsWith(hex) }
    }

    @Test
    fun an_empty_selection_shreds_nothing() {
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        val vo = objects(catalog)
        val id = import(vo, 1)

        assertEquals(Outcome.Ok(0L), vo.shredObjects(emptyList()))
        assertEquals(1, listed(vo, catalog))
        assertTrue(storage.objectExists(id))
    }
}
