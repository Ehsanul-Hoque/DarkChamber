package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.app.di.CatalogProvider
import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DchmFormat
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.KeyStoreCodec
import dev.ehsanfahad.darkchamber.core.crypto.SecureKeyStore
import dev.ehsanfahad.darkchamber.core.crypto.Slot
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.db.Items
import dev.ehsanfahad.darkchamber.core.logging.logVaultError
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.flatMap
import dev.ehsanfahad.darkchamber.core.model.map
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.write

/**
 * The fail-closed cold-open of the vault — the composition root's single entry point.
 *
 * Resolves the app-private vault root, opens the catalog + storage, and creates-or-unlocks a
 * [VaultSession] (by passphrase this cycle — the biometric / hardware-slot path is 5b). Every step
 * is `Outcome`-typed and the first failure aborts the open, never a half-initialized vault (D6). No
 * exception crosses out; failures are logged as a redacted code only (hard rule #4).
 *
 * Constructed by Koin ([dev.ehsanfahad.darkchamber.app.di.commonAppModule]).
 *
 * @param rootResolver resolves + prepares the backup-excluded vault root.
 * @param engine the sole cryptographic primitive seam (hard rule #2).
 * @param catalogProvider opens the catalog over a resolved root (platform-bound driver creation).
 * @param secureKeyStore the HARDWARE-slot wrap seam (used by the biometric path in 5b).
 */
class VaultBootstrap(
    private val rootResolver: VaultRootResolver,
    private val engine: CryptoEngine,
    private val catalogProvider: CatalogProvider,
    private val secureKeyStore: SecureKeyStore,
) {
    /**
     * Cold-open the vault: resolve root → open catalog + storage → create-vault (first run, keystore
     * persisted) or unlock (reopen) with [passphrase]. The [passphrase] is consumed (zeroized) here
     * whether or not the open succeeds (hard rule #5). Returns the live [VaultSession]; the caller
     * owns [VaultSession.close].
     */
    fun openOrCreateVault(passphrase: ByteArray): Outcome<VaultSession> =
        open(passphrase).map { it.session }

    /**
     * A 5a bring-up probe: open-or-create the vault with a fixed diagnostic passphrase, round-trip
     * one encrypted object end-to-end (encrypt → store → catalog → read → decrypt → assert), then
     * close the session. Proves the whole engine composes on a real device/simulator. This is the
     * only vault user this cycle (no UI yet); the real user passphrase / biometric unlock arrives in
     * 5b. On any failure a redacted code is logged and the failure returned.
     */
    fun selfCheck(): Outcome<Unit> =
        when (val opened = open(diagnosticPassphrase())) {
            is Outcome.Err -> {
                logVaultError(STAGE_OPEN, opened.error)
                opened
            }
            is Outcome.Ok -> {
                val vault = opened.value
                val result = roundTrip(vault)
                vault.session.close()
                if (result is Outcome.Err) logVaultError(STAGE_ROUND_TRIP, result.error)
                result
            }
        }

    private class OpenVault(
        val session: VaultSession,
        val storage: VaultStorage,
        val catalog: CatalogStore,
    )

    private fun open(passphrase: ByteArray): Outcome<OpenVault> {
        try {
            val root = when (val r = rootResolver.resolve()) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
            val storage = try {
                VaultStorage(root)
            } catch (e: Exception) {
                return Outcome.Err(VaultError.Io(e))
            }
            val catalog = when (val c = catalogProvider.open(root)) {
                is Outcome.Err -> return c
                is Outcome.Ok -> c.value
            }

            val kdf = DefaultKeyDerivation(engine)
            val session = when (val existing = storage.readKeyStore()) {
                is Outcome.Err -> return existing
                is Outcome.Ok -> {
                    val bytes = existing.value
                    if (bytes == null) {
                        // First run: create the vault and persist its keystore before returning.
                        val created = VaultSession.createVault(engine, kdf, passphrase)
                        when (val written = storage.writeKeyStore(KeyStoreCodec.encode(created.keyStore))) {
                            is Outcome.Err -> {
                                created.session.close()
                                return written
                            }
                            is Outcome.Ok -> created.session
                        }
                    } else {
                        // Reopen: decode the keystore and unlock the PASSPHRASE slot.
                        when (val blob = KeyStoreCodec.decode(bytes)) {
                            is Outcome.Err -> return blob
                            is Outcome.Ok -> when (
                                val s = VaultSession.unlock(engine, kdf, blob.value, passphrase, Slot.PASSPHRASE)
                            ) {
                                is Outcome.Err -> return s
                                is Outcome.Ok -> s.value
                            }
                        }
                    }
                }
            }
            return Outcome.Ok(OpenVault(session, storage, catalog))
        } finally {
            // #5: createVault/unlock consume the passphrase on the happy path; zeroize on every
            // early-exit path too (a double zero-fill is a harmless no-op).
            passphrase.fill(0)
        }
    }

    private fun roundTrip(vault: OpenVault): Outcome<Unit> {
        val codec = DchmCodec(engine)
        val plaintext = SELF_CHECK_PLAINTEXT
        val fileId = engine.randomBytes(OBJECT_ID_SIZE)
        val wrappedDek = vault.session.generateDek()

        val objectCipher = when (val c = seal(vault.session, wrappedDek, fileId, plaintext, codec)) {
            is Outcome.Err -> return c
            is Outcome.Ok -> c.value
        }
        val metaCipher = when (val m = seal(vault.session, wrappedDek, fileId, SELF_CHECK_META, codec)) {
            is Outcome.Err -> return m
            is Outcome.Ok -> m.value
        }

        val objBuf = Buffer().apply { write(objectCipher) }
        when (val w = vault.storage.writeObject(fileId) { sink -> sink.write(objBuf, objBuf.size) }) {
            is Outcome.Err -> return w
            is Outcome.Ok -> Unit
        }

        // The row carries ids + opaque encrypted blobs only (enc_meta / dek_wrapped) — never content.
        val item = Items(
            id = fileId,
            album_id = null,
            kind = 0,
            size = plaintext.size.toLong(),
            created_at = 0L,
            enc_meta = metaCipher,
            dek_wrapped = wrappedDek,
        )
        when (val u = vault.catalog.upsertItem(item)) {
            is Outcome.Err -> return u
            is Outcome.Ok -> Unit
        }

        val fetched = when (val g = vault.catalog.getItem(fileId)) {
            is Outcome.Err -> return g
            is Outcome.Ok -> g.value
                ?: return Outcome.Err(VaultError.Db(IllegalStateException("catalog row missing after upsert")))
        }

        val cipherBack = when (val r = vault.storage.readObject(fetched.id) { src -> src.buffered().readByteArray() }) {
            is Outcome.Err -> return r
            is Outcome.Ok -> r.value
        }

        val plainBack = when (val d = unseal(vault.session, fetched.dek_wrapped, fetched.id, cipherBack, codec)) {
            is Outcome.Err -> return d
            is Outcome.Ok -> d.value
        }

        return if (plainBack.contentEquals(plaintext)) {
            Outcome.Ok(Unit)
        } else {
            // Decrypt succeeded but bytes differ — a composition bug, surfaced as an integrity failure.
            Outcome.Err(VaultError.Crypto.IntegrityFailure)
        }
    }

    /** Encrypt [plaintext] into a `.dchm` container under the wrapped DEK; returns the container bytes. */
    private fun seal(
        session: VaultSession,
        wrappedDek: ByteArray,
        fileId: ByteArray,
        plaintext: ByteArray,
        codec: DchmCodec,
    ): Outcome<ByteArray> =
        session.useDek(wrappedDek) { raw ->
            val out = Buffer()
            codec.encryptContainer(
                dek = raw,
                noncePrefix = engine.randomBytes(DchmFormat.NONCE_PREFIX_SIZE),
                createdAt = 0L,
                fileId = fileId,
                originalName = "",
                mime = "application/octet-stream",
                chunkSize = DchmFormat.DEFAULT_CHUNK_SIZE,
                openSource = { Buffer().apply { write(plaintext) } },
                sink = out,
            ).map { out.readByteArray() }
        }.flatMap { it }

    /** Decrypt a `.dchm` container back to plaintext under the wrapped DEK. */
    private fun unseal(
        session: VaultSession,
        wrappedDek: ByteArray,
        fileId: ByteArray,
        container: ByteArray,
        codec: DchmCodec,
    ): Outcome<ByteArray> =
        session.useDek(wrappedDek) { raw ->
            val out = Buffer()
            codec.decryptContainer(raw, fileId, Buffer().apply { write(container) }, out)
                .map { out.readByteArray() }
        }.flatMap { it }

    /** Fresh diagnostic passphrase for [selfCheck] (consumed on use). Not a user secret — 5a only. */
    private fun diagnosticPassphrase(): ByteArray = "darkchamber:selfcheck:v1".encodeToByteArray()

    private companion object {
        const val STAGE_OPEN = "vault-open"
        const val STAGE_ROUND_TRIP = "vault-round-trip"
        const val OBJECT_ID_SIZE = 16
        val SELF_CHECK_PLAINTEXT = "darkchamber-selfcheck-plaintext".encodeToByteArray()
        val SELF_CHECK_META = "darkchamber-selfcheck-meta".encodeToByteArray()
    }
}
