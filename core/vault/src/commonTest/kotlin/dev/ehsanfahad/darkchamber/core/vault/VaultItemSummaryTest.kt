package dev.ehsanfahad.darkchamber.core.vault

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gallery listing: `observeItems` joins each catalog row to its sealed metadata so features get
 * domain summaries instead of SQLDelight row types, and so no feature needs `core:db`.
 *
 * The load-bearing case is the **damaged row**: a row whose `enc_meta` will not open must still be
 * listed, carrying its error, never silently dropped — an omitted item would hide tampering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultItemSummaryTest {
    private val testDir = Path(SystemTemporaryDirectory, "vis-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun newStorage(): VaultStorage {
        SystemFileSystem.createDirectories(testDir)
        return VaultStorage(testDir)
    }

    private fun newSession(): VaultSession =
        VaultSession.createVault(engine, kdf, "correct horse battery".encodeToByteArray(), cheapKdf).session

    private fun source(data: ByteArray): () -> RawSource = { Buffer().apply { write(data) } }

    // Dispatchers.Unconfined, not a TestDispatcher: a test dispatcher built outside runTest carries
    // its own scheduler, and runTest then waits forever for a scheduler it does not own.
    private fun fixture(mapContext: CoroutineContext = Dispatchers.Unconfined): Pair<VaultObjects, CatalogStore> {
        val catalog = (CatalogStore.create(testSqlDriver(), UnconfinedTestDispatcher()) as Outcome.Ok).value
        val rng = Random(11).let { r -> { n: Int -> r.nextBytes(n) } }
        val objects = VaultObjects(
            session = newSession(),
            storage = newStorage(),
            catalog = catalog,
            codec = DchmCodec(engine),
            clock = { 1_000L },
            random = rng,
            mapContext = mapContext,
        )
        return objects to catalog
    }

    private fun VaultObjects.importFile(name: String, mime: String, data: ByteArray): ByteArray =
        (
            importObject(
                kind = VaultItemKind.FILE.code,
                name = name,
                mime = mime,
                chunkSize = 64,
                openSource = source(data),
            ) as Outcome.Ok
            ).value

    @Test
    fun observe_items_emits_one_summary_per_row_with_decrypted_meta() = runTest {
        val (vo, catalog) = fixture()
        val id = vo.importFile("holiday.jpg", "image/jpeg", ByteArray(130) { it.toByte() })

        vo.observeItems().test {
            val summaries = awaitItem()
            assertEquals(1, summaries.size, "one row imported")
            val only = summaries.single()
            assertContentEquals(id, only.id, "id survives")
            assertEquals(VaultItemKind.FILE, only.kind, "kind is decoded, not a raw Int")
            assertEquals(130L, only.size, "plaintext size from the row")
            assertEquals(1_000L, only.createdAt, "created_at from the row")
            val meta = only.meta
            assertTrue(meta is Outcome.Ok, "meta should open, was $meta")
            assertEquals("holiday.jpg", meta.value.name)
            assertEquals("image/jpeg", meta.value.mime)
            cancelAndIgnoreRemainingEvents()
        }
        catalog.close()
    }

    @Test
    fun a_row_whose_meta_cannot_be_opened_is_listed_as_damaged_not_dropped() = runTest {
        val (vo, catalog) = fixture()
        vo.importFile("intact.txt", "text/plain", byteArrayOf(1, 2, 3))
        val damagedId = vo.importFile("tampered.txt", "text/plain", byteArrayOf(4, 5, 6))

        // Flip a bit in the sealed metadata — the GCM tag must no longer verify.
        val row = (catalog.getItem(damagedId) as Outcome.Ok).value!!
        val tampered = row.enc_meta.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertTrue(catalog.upsertItem(row.copy(enc_meta = tampered)) is Outcome.Ok)

        vo.observeItems().test {
            val summaries = awaitItem()
            assertEquals(2, summaries.size, "the damaged row is still listed")

            val damaged = summaries.single { it.id.contentEquals(damagedId) }
            val meta = damaged.meta
            assertTrue(meta is Outcome.Err, "damaged meta must carry the error, was $meta")
            assertTrue(meta.error is VaultError.Crypto, "a failed unseal is a crypto error, was ${meta.error}")

            // The row's plaintext index columns are unaffected — the item can still be shown.
            assertEquals(3L, damaged.size)
            assertEquals(VaultItemKind.FILE, damaged.kind)

            val intact = summaries.single { !it.id.contentEquals(damagedId) }
            assertTrue(intact.meta is Outcome.Ok, "one damaged row must not poison its neighbours")
            cancelAndIgnoreRemainingEvents()
        }
        catalog.close()
    }

    @Test
    fun observe_items_filters_by_album() = runTest {
        val (vo, catalog) = fixture()
        val album = byteArrayOf(0xA1.toByte(), 0xB2.toByte())
        val filedId = vo.importFile("filed.txt", "text/plain", byteArrayOf(1))
        vo.importFile("unfiled.txt", "text/plain", byteArrayOf(2))

        val filed = (catalog.getItem(filedId) as Outcome.Ok).value!!
        assertTrue(catalog.upsertItem(filed.copy(album_id = album)) is Outcome.Ok)

        vo.observeItems(albumId = album).test {
            val summaries = awaitItem()
            assertEquals(1, summaries.size, "only the filed item")
            assertContentEquals(filedId, summaries.single().id)
            cancelAndIgnoreRemainingEvents()
        }

        vo.observeItems().test {
            assertEquals(2, awaitItem().size, "the unfiltered listing still sees both")
            cancelAndIgnoreRemainingEvents()
        }
        catalog.close()
    }

    @Test
    fun an_import_re_emits_the_listing() = runTest {
        val (vo, catalog) = fixture()

        vo.observeItems().test {
            assertEquals(emptyList(), awaitItem(), "starts empty")

            vo.importFile("first.txt", "text/plain", byteArrayOf(1))
            assertEquals(1, awaitItem().size, "re-emits after an import")

            vo.importFile("second.txt", "text/plain", byteArrayOf(2))
            val both = awaitItem()
            assertEquals(2, both.size, "re-emits again")
            assertEquals(listOf("first.txt", "second.txt"), both.map { (it.meta as Outcome.Ok).value.name })
            cancelAndIgnoreRemainingEvents()
        }
        catalog.close()
    }

    @Test
    fun a_shred_re_emits_the_listing_without_the_item() = runTest {
        val (vo, catalog) = fixture()
        val id = vo.importFile("doomed.txt", "text/plain", byteArrayOf(9))

        vo.observeItems().test {
            assertEquals(1, awaitItem().size)

            assertTrue(vo.shredObject(id) is Outcome.Ok)
            assertEquals(emptyList(), awaitItem(), "the shredded item leaves the listing")
            cancelAndIgnoreRemainingEvents()
        }
        catalog.close()
    }

    /**
     * The per-row unseal is AES-GCM plus a CBOR decode. It must not run wherever the caller
     * collects — on a gallery that is the UI thread, once per row, on every re-emission — so
     * `observeItems` moves it onto its own context. Asserted by counting dispatches: without the
     * `flowOn` the mapping stays in the collector's context and this dispatcher is never used.
     */
    @Test
    fun the_metadata_decrypt_runs_off_the_collectors_context() = runTest {
        // Delegates to a dispatcher sharing runTest's scheduler, so the work it counts actually
        // runs. (Wrapping Dispatchers.Unconfined does not work: this wrapper reports that dispatch
        // is needed, and Unconfined.dispatch only services yield() — the emission never arrives.)
        val counting = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val (vo, catalog) = fixture(mapContext = counting)
        vo.importFile("a.txt", "text/plain", byteArrayOf(1))

        vo.observeItems().test {
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(counting.dispatches > 0, "the row mapping must be dispatched to the map context")
        catalog.close()
    }

    @Test
    fun summaries_with_equal_content_are_equal() {
        val meta = Outcome.Ok(ObjectMeta("a.txt", "text/plain"))
        val one = VaultItemSummary(byteArrayOf(1, 2), VaultItemKind.FILE, 3L, 4L, meta)
        val two = VaultItemSummary(byteArrayOf(1, 2), VaultItemKind.FILE, 3L, 4L, meta)
        val other = VaultItemSummary(byteArrayOf(9, 9), VaultItemKind.FILE, 3L, 4L, meta)

        // The id is a ByteArray, so the generated data-class equals would compare by identity and
        // every re-emission would look like a change to the UI list.
        assertEquals(one, two, "equal content must compare equal")
        assertEquals(one.hashCode(), two.hashCode(), "equal values must share a hash")
        assertTrue(one != other, "a different id must not compare equal")
    }
}

/** Records whether work was actually handed to it — the evidence that `flowOn` is in effect. */
private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var dispatches = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches++
        delegate.dispatch(context, block)
    }
}
