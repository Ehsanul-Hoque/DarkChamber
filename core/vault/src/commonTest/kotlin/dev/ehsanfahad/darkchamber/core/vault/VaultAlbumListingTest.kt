package dev.ehsanfahad.darkchamber.core.vault

import app.cash.turbine.test
import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.IncrementalHash
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.Albums
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The album grid's listing: `observeAlbums` joins each album row to its decrypted name, and takes
 * the count and the cover from the catalog's plaintext columns without decrypting a single item.
 *
 * Two load-bearing cases live here. The **damaged album** — a row whose `enc_name` will not open
 * must still be listed, carrying its error, with its id, count and cover intact; an album dropped
 * from the grid hides tampering *and* strands whatever is filed inside it. And the **cost shape**:
 * the number of decrypts is the number of albums, never the number of items, which is what makes
 * this screen affordable in a large vault.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultAlbumListingTest {
    private val testDir = Path(SystemTemporaryDirectory, "val-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)
    private val session = VaultSession.createVault(
        engine,
        kdf,
        "correct horse battery".encodeToByteArray(),
        cheapKdf,
    ).session

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    // One session held as a field rather than built per fixture: these tests seal album names
    // themselves to plant a damaged one, so the sealing key has to be the same key the listing opens
    // with.
    private fun fixture(
        mapContext: CoroutineContext = Dispatchers.Unconfined,
    ): Pair<VaultObjects, CatalogStore> {
        SystemFileSystem.createDirectories(testDir)
        val catalog = (CatalogStore.create(testSqlDriver(), UnconfinedTestDispatcher()) as Outcome.Ok).value
        var now = 1_000L
        val objects = VaultObjects(
            session = session,
            storage = VaultStorage(testDir),
            catalog = catalog,
            codec = DchmCodec(engine),
            clock = { now++ },
            random = Random(11).let { r -> { n: Int -> r.nextBytes(n) } },
            mapContext = mapContext,
        )
        return objects to catalog
    }

    private fun album(id: Byte, encName: ByteArray, createdAt: Long) = Albums(
        id = byteArrayOf(id),
        kek_variant = 0,
        enc_name = encName,
        created_at = createdAt,
    )

    private fun VaultObjects.importInto(albumId: ByteArray?, name: String, catalog: CatalogStore): ByteArray {
        val id = (
            importObject(
                kind = VaultItemKind.FILE.code,
                name = name,
                mime = "text/plain",
                chunkSize = 64,
                openSource = { Buffer().apply { write(byteArrayOf(1, 2, 3)) } as RawSource },
            ) as Outcome.Ok
            ).value
        if (albumId != null) {
            // The assignment path proper arrives with the use cases; here the point is only to get a
            // row filed so the derived columns have something to derive from.
            catalog.updateItemAlbum(listOf(id), albumId).let { assertIs<Outcome.Ok<Unit>>(it) }
        }
        return id
    }

    @Test
    fun an_album_is_listed_with_its_opened_name_count_and_cover() = runTest {
        val (vo, catalog) = fixture()
        val sealed = session.sealAlbumMeta(AlbumMeta("Receipts"))
        assertIs<Outcome.Ok<Unit>>(catalog.upsertAlbum(album(1, sealed, createdAt = 500L)))
        vo.importInto(byteArrayOf(1), "older.txt", catalog)
        val newest = vo.importInto(byteArrayOf(1), "newer.txt", catalog)

        vo.observeAlbums().test {
            val listed = awaitItem().single()

            assertEquals(Outcome.Ok("Receipts"), listed.name, "the name is opened for display")
            assertContentEquals(byteArrayOf(1), listed.id)
            assertEquals(2, listed.itemCount, "counted from the plaintext column, not from decrypting")
            assertContentEquals(newest, listed.coverItemId, "the newest item represents the album")
            assertEquals(500L, listed.createdAt)
            cancelAndIgnoreRemainingEvents()
        }
        catalog.close()
    }

    /**
     * The case the whole `Outcome` is for. The damaged album keeps everything the catalog can still
     * tell us about it — which is everything except its name — so the user can see that it exists,
     * see how much is in it, and delete it.
     */
    @Test
    fun an_album_whose_name_will_not_open_is_listed_with_the_error() = runTest {
        val (vo, catalog) = fixture()
        val good = session.sealAlbumMeta(AlbumMeta("Receipts"))
        val damaged = session.sealAlbumMeta(AlbumMeta("Payslips")).also {
            it[it.lastIndex] = (it[it.lastIndex] + 1).toByte()
        }
        assertIs<Outcome.Ok<Unit>>(catalog.upsertAlbum(album(1, good, createdAt = 100L)))
        assertIs<Outcome.Ok<Unit>>(catalog.upsertAlbum(album(2, damaged, createdAt = 200L)))
        val stranded = vo.importInto(byteArrayOf(2), "in-the-damaged-album.txt", catalog)

        vo.observeAlbums().test {
            val listed = awaitItem()

            assertEquals(2, listed.size, "the damaged album is present, not filtered out")
            val broken = listed.single { it.id.contentEquals(byteArrayOf(2)) }
            assertIs<Outcome.Err>(broken.name, "it carries why, rather than a blank or a placeholder")
            assertEquals(
                VaultError.Crypto.IntegrityFailure,
                broken.name.error,
                "surfaced as possible tampering",
            )
            assertEquals(1, broken.itemCount, "its count still reads — that is a plaintext column")
            assertContentEquals(stranded, broken.coverItemId, "and so does its cover")

            val fine = listed.single { it.id.contentEquals(byteArrayOf(1)) }
            assertEquals(Outcome.Ok("Receipts"), fine.name, "one damaged album must not poison its neighbour")
            cancelAndIgnoreRemainingEvents()
        }
        catalog.close()
    }

    @Test
    fun an_empty_album_lists_with_no_cover_and_a_zero_count() = runTest {
        val (vo, catalog) = fixture()
        assertIs<Outcome.Ok<Unit>>(
            catalog.upsertAlbum(album(1, session.sealAlbumMeta(AlbumMeta("Empty")), createdAt = 100L)),
        )

        vo.observeAlbums().test {
            val listed = awaitItem().single()

            assertEquals(0, listed.itemCount)
            assertNull(listed.coverItemId, "an album with nothing in it has nothing to show")
            assertEquals(Outcome.Ok("Empty"), listed.name, "and is still named")
            cancelAndIgnoreRemainingEvents()
        }
        catalog.close()
    }

    /**
     * Liveness through the vault layer, not just the catalog's. The count and cover change while the
     * album row is untouched, so this also re-proves across this mapping that the listing follows
     * writes to `items`.
     */
    @Test
    fun the_listing_follows_item_writes_without_the_album_row_changing() = runTest {
        val (vo, catalog) = fixture()
        assertIs<Outcome.Ok<Unit>>(
            catalog.upsertAlbum(album(1, session.sealAlbumMeta(AlbumMeta("Trip")), createdAt = 100L)),
        )

        vo.observeAlbums().test {
            assertEquals(0, awaitItem().single().itemCount, "starts empty")

            val first = vo.importInto(byteArrayOf(1), "one.txt", catalog)
            // The import writes a row, then the assignment writes it again — two writes, so two
            // emissions, and the first of them has not been filed yet.
            awaitItem()
            val afterFiling = awaitItem().single()
            assertEquals(1, afterFiling.itemCount, "the count follows a filing")
            assertContentEquals(first, afterFiling.coverItemId)

            vo.shredObject(first).let { assertIs<Outcome.Ok<Unit>>(it) }
            val afterShred = awaitItem().single()
            assertEquals(0, afterShred.itemCount, "and follows a shred")
            assertNull(afterShred.coverItemId, "losing its last item loses its cover, not its row")
            cancelAndIgnoreRemainingEvents()
        }
        catalog.close()
    }

    /**
     * The name unseal is AES-GCM plus a CBOR decode, and it must not run wherever the caller
     * collects — on the album grid that is the UI thread, once per album, on every re-emission.
     * Counted the same way the item listing counts it: without the `flowOn` the mapping stays in the
     * collector's context and this dispatcher is never touched.
     */
    @Test
    fun the_name_decrypt_runs_off_the_collectors_context() = runTest {
        // A dispatcher sharing runTest's scheduler, so the work it counts actually runs — wrapping
        // Unconfined does not work, for the reason the item listing's equivalent records.
        val counting = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val (vo, catalog) = fixture(mapContext = counting)
        assertIs<Outcome.Ok<Unit>>(
            catalog.upsertAlbum(album(1, session.sealAlbumMeta(AlbumMeta("Trip")), createdAt = 100L)),
        )

        vo.observeAlbums().test {
            assertEquals(1, awaitItem().size)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(counting.dispatches > 0, "the name mapping must be dispatched to the map context")
        catalog.close()
    }

    /**
     * The cost shape, counted rather than asserted by assertion-free assertion: one AEAD open per
     * album per emission, and none per item.
     *
     * This is the property the whole design of the listing rests on, and it is invisible in any other
     * test — a listing that derived a count, a cover or a total size from item metadata would still
     * produce the right numbers here and simply cost one open per file per emission, on a screen that
     * re-emits whenever anything is imported. The opens are tallied through a counting engine, so the
     * measurement is of the real session's work and not of a stub's.
     */
    @Test
    fun the_listing_opens_one_blob_per_album_and_none_per_item() = runTest {
        val counting = CountingEngine(engine)
        val countedSession = VaultSession.createVault(
            counting,
            DefaultKeyDerivation(counting),
            "correct horse battery".encodeToByteArray(),
            cheapKdf,
        ).session
        SystemFileSystem.createDirectories(testDir)
        val catalog = (CatalogStore.create(testSqlDriver(), UnconfinedTestDispatcher()) as Outcome.Ok).value
        var now = 1_000L
        val vo = VaultObjects(
            session = countedSession,
            storage = VaultStorage(testDir),
            catalog = catalog,
            codec = DchmCodec(counting),
            clock = { now++ },
            random = Random(11).let { r -> { n: Int -> r.nextBytes(n) } },
            mapContext = Dispatchers.Unconfined,
        )
        assertIs<Outcome.Ok<Unit>>(
            catalog.upsertAlbum(album(1, countedSession.sealAlbumMeta(AlbumMeta("Trip")), createdAt = 100L)),
        )
        assertIs<Outcome.Ok<Unit>>(
            catalog.upsertAlbum(album(2, countedSession.sealAlbumMeta(AlbumMeta("Receipts")), createdAt = 200L)),
        )
        repeat(5) { vo.importInto(byteArrayOf(1), "file-$it.txt", catalog) }

        // Measured from here: the imports above open blobs of their own, and those are not what this
        // is about.
        val before = counting.opens
        vo.observeAlbums().test {
            val listed = awaitItem()
            assertEquals(listOf(5, 0), listed.map { it.itemCount }, "five items in one album, none in the other")
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(
            2,
            counting.opens - before,
            "two albums, two opens — not seven, which is what reading the items would cost",
        )
        catalog.close()
    }
}

/**
 * Counts AEAD opens and delegates everything else. Not a fake engine: the crypto underneath is the
 * real one, because a stubbed open would make the count a measurement of the stub.
 */
private class CountingEngine(private val delegate: CryptoEngine) : CryptoEngine {
    var opens = 0
        private set

    override fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): Outcome<ByteArray> {
        opens++
        return delegate.aeadOpen(key, nonce, aad, ciphertext)
    }

    override fun aeadSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        delegate.aeadSeal(key, nonce, aad, plaintext)

    override fun sha256(bytes: ByteArray): ByteArray = delegate.sha256(bytes)

    override fun newSha256(): IncrementalHash = delegate.newSha256()

    override fun randomBytes(n: Int): ByteArray = delegate.randomBytes(n)

    override fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLenBytes: Int): ByteArray =
        delegate.pbkdf2Sha256(password, salt, iterations, dkLenBytes)

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, lenBytes: Int): ByteArray =
        delegate.hkdfSha256(ikm, salt, info, lenBytes)

    override fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int,
        outLenBytes: Int,
    ): ByteArray = delegate.argon2id(password, salt, memoryKib, iterations, parallelism, outLenBytes)
}
