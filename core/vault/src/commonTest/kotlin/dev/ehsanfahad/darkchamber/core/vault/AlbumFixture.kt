package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.IncrementalHash
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.Albums
import app.cash.sqldelight.db.SqlDriver
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.assertIs

/**
 * A real vault — real engine, real session, real storage, in-memory catalog — for the album tests.
 *
 * Shared rather than copied into each album test: the listing, the writes and the delete all need
 * the same thing, and the session has to be the *same* session that opens what these tests seal, so
 * the wiring is easy to get subtly wrong in a way that still compiles.
 *
 * Pass [engine] to observe the crypto — see [CountingCryptoEngine], which is how the listing proves
 * it opens one blob per album and none per item.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AlbumFixture(
    mapContext: CoroutineContext = Dispatchers.Unconfined,
    val engine: CryptoEngine = CryptographyKotlinEngine(),
    // Pass a wrapped driver to interrupt a multi-write operation partway — see FailingSqlDriver,
    // which is how the album delete's ordering is pinned.
    driver: SqlDriver = testSqlDriver(),
) {
    private val dir = Path(SystemTemporaryDirectory, "alb-${Random.nextULong()}")

    val session: VaultSession = VaultSession.createVault(
        engine,
        DefaultKeyDerivation(engine),
        "correct horse battery".encodeToByteArray(),
        // Deliberately weak Argon2 parameters: these tests exercise album plumbing, not the KDF, and
        // production parameters would add seconds to every one of them.
        PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1),
    ).session

    val catalog: CatalogStore

    val objects: VaultObjects

    private var now = 1_000L

    init {
        SystemFileSystem.createDirectories(dir)
        catalog = (CatalogStore.create(driver, UnconfinedTestDispatcher()) as Outcome.Ok).value
        objects = VaultObjects(
            session = session,
            storage = VaultStorage(dir),
            catalog = catalog,
            codec = DchmCodec(engine),
            clock = { now++ },
            random = Random(11).let { r -> { n: Int -> r.nextBytes(n) } },
            mapContext = mapContext,
        )
    }

    fun close() {
        catalog.close()
        deleteRecursively(dir)
    }

    /** An album name sealed with this fixture's session — what a catalog row's `enc_name` holds. */
    fun sealed(name: String): ByteArray = session.sealAlbumMeta(AlbumMeta(name))

    /** The same, with a byte flipped: a row whose name will not open. */
    fun damaged(name: String): ByteArray =
        sealed(name).also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }

    fun albumRow(id: Byte, encName: ByteArray, createdAt: Long = 100L) = Albums(
        id = byteArrayOf(id),
        kek_variant = 0,
        enc_name = encName,
        created_at = createdAt,
    )

    fun putAlbum(id: Byte, encName: ByteArray, createdAt: Long = 100L): ByteArray {
        assertIs<Outcome.Ok<Unit>>(catalog.upsertAlbum(albumRow(id, encName, createdAt)))
        return byteArrayOf(id)
    }

    /**
     * Import a small file and, when [albumId] is given, file it there.
     *
     * Files through the catalog directly: the assignment use case arrives later, and all these tests
     * need is a row whose album is set so the derived count and cover have something to derive from.
     */
    fun importInto(albumId: ByteArray?, name: String): ByteArray {
        val id = (
            objects.importObject(
                kind = VaultItemKind.FILE.code,
                name = name,
                mime = "text/plain",
                chunkSize = 64,
                openSource = { Buffer().apply { write(byteArrayOf(1, 2, 3)) } as RawSource },
            ) as Outcome.Ok
            ).value
        if (albumId != null) {
            assertIs<Outcome.Ok<Unit>>(catalog.updateItemAlbum(listOf(id), albumId))
        }
        return id
    }
}

/**
 * Counts AEAD opens and delegates everything else. Not a fake engine: the crypto underneath is the
 * real one, because a stubbed open would make the count a measurement of the stub.
 */
internal class CountingCryptoEngine(private val delegate: CryptoEngine) : CryptoEngine {
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
