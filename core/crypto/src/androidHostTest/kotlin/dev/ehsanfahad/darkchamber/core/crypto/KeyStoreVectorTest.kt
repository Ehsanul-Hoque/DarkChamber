package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.getOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The KeyStore format-vector gate (FORMAT.md §3.1 / §7), sibling to [FormatVectorTest]. Committed
 * vectors under `docs/format-test-vectors/keystore/` fix the passphrase- and recovery-wrapped
 * MasterKey byte-for-byte, so a future code change that shifts the blob layout — i.e. would stop
 * old vaults opening — fails here. Runs at the deterministic [KeyStoreCodec] / [MasterKeyWrap] layer
 * (fixed salts/nonces/MK); reduced iterations (1000) keep the gate fast.
 */
@OptIn(ExperimentalStdlibApi::class)
class KeyStoreVectorTest {
    @Serializable
    data class KeyStoreVector(
        val name: String,
        val format_version: Int,
        val mk: String,
        val passphrase_utf8: String,
        // Which KDF the PASSPHRASE slot uses. The matching *_ fields below must be present.
        val passphrase_kdf: String = "pbkdf2",
        // PBKDF2 passphrase slot.
        val pbkdf2_salt: String? = null,
        val pbkdf2_iterations: Int? = null,
        val pbkdf2_nonce: String? = null,
        // Argon2id passphrase slot.
        val argon2_salt: String? = null,
        val argon2_memory_kib: Int? = null,
        val argon2_iterations: Int? = null,
        val argon2_parallelism: Int? = null,
        val argon2_nonce: String? = null,
        // HKDF recovery slot.
        val recovery_key: String,
        val hkdf_salt: String,
        val hkdf_info: String,
        val hkdf_nonce: String,
        val blob: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)

    private val vectorsDir: File =
        File(requireNotNull(System.getProperty("dchm.vectors.dir")) { "dchm.vectors.dir not set" }, "keystore")

    private fun vectors(): List<KeyStoreVector> =
        json.decodeFromString(File(vectorsDir, "keystore.json").readText())

    private fun KeyStoreVector.passphraseEntry(): WrapEntry = when (passphrase_kdf) {
        "pbkdf2" -> MasterKeyWrap.wrap(
            engine, kdf, mk.hexToByteArray(), passphrase_utf8.encodeToByteArray(), Slot.PASSPHRASE,
            KdfParams.Pbkdf2(pbkdf2_salt!!.hexToByteArray(), pbkdf2_iterations!!), pbkdf2_nonce!!.hexToByteArray(),
        )
        "argon2id" -> MasterKeyWrap.wrap(
            engine, kdf, mk.hexToByteArray(), passphrase_utf8.encodeToByteArray(), Slot.PASSPHRASE,
            KdfParams.Argon2id(argon2_salt!!.hexToByteArray(), argon2_memory_kib!!, argon2_iterations!!, argon2_parallelism!!),
            argon2_nonce!!.hexToByteArray(),
        )
        else -> error("$name: unknown passphrase_kdf '$passphrase_kdf'")
    }

    private fun KeyStoreVector.recoveryEntry() = MasterKeyWrap.wrap(
        engine, kdf, mk.hexToByteArray(), recovery_key.hexToByteArray(), Slot.RECOVERY,
        KdfParams.Hkdf(hkdf_salt.hexToByteArray(), hkdf_info.encodeToByteArray()), hkdf_nonce.hexToByteArray(),
    )

    private fun KeyStoreVector.build() =
        KeyStoreBlob(format_version, listOf(passphraseEntry(), recoveryEntry()))

    @Test
    fun positive_vectors_encode_to_the_committed_bytes_and_unlock_both_slots() {
        val all = vectors()
        assertTrue(all.isNotEmpty(), "no keystore vectors found under $vectorsDir")
        for (v in all) {
            val encoded = KeyStoreCodec.encode(v.build()).toHexString()

            if (v.blob.isBlank()) {
                // Bootstrap: no committed bytes yet — emit them so they can be pinned, then fail loudly.
                File("build/keystore-vectors").apply { mkdirs() }.resolve("${v.name}.hex").writeText(encoded)
                fail("${v.name}: 'blob' not committed. Wrote actual bytes to build/keystore-vectors/${v.name}.hex")
            }

            assertEquals(v.blob, encoded, "${v.name}: KeyStore blob mismatch (layout drift — old vaults would break)")

            val decoded = KeyStoreCodec.decode(v.blob.hexToByteArray())
            assertTrue(decoded is Outcome.Ok, "${v.name}: committed blob failed to decode: $decoded")
            val blob = decoded.value

            val pw = blob.wraps.first { it.slot == Slot.PASSPHRASE }
            val rec = blob.wraps.first { it.slot == Slot.RECOVERY }
            assertContentEquals(v.mk.hexToByteArray(), MasterKeyWrap.unwrap(engine, kdf, pw, v.passphrase_utf8.encodeToByteArray()).getOrNull(), "${v.name}: passphrase slot")
            assertContentEquals(v.mk.hexToByteArray(), MasterKeyWrap.unwrap(engine, kdf, rec, v.recovery_key.hexToByteArray()).getOrNull(), "${v.name}: recovery slot")
        }
    }

    @Test
    fun negative_cases_fail_with_the_stated_error() {
        for (v in vectors().filter { it.blob.isNotBlank() }) {
            val pw = v.passphraseEntry()
            val rec = v.recoveryEntry()

            // Wrong passphrase → possible tampering, not "update the app".
            assertEquals(
                Outcome.Err(VaultError.Crypto.IntegrityFailure),
                MasterKeyWrap.unwrap(engine, kdf, pw, "not the passphrase".encodeToByteArray()),
                "${v.name}: wrong passphrase",
            )

            // A kdfId that disagrees with its params → malformed (relabel the passphrase wrap with a
            // different id than its params claim, without touching the committed wrap bytes).
            val wrongId = if (pw.kdfParams is KdfParams.Argon2id) KdfId.PBKDF2_SHA256 else KdfId.ARGON2ID
            val mismatched = WrapEntry(pw.slot, wrongId, pw.kdfParams, pw.nonce, pw.wrappedMk)
            assertEquals(
                Outcome.Err(VaultError.Crypto.MalformedContainer),
                KeyStoreCodec.decode(KeyStoreCodec.encode(KeyStoreBlob(v.format_version, listOf(mismatched, rec)))),
                "${v.name}: kdfId/params mismatch",
            )

            // A format version above supported → update the app.
            assertEquals(
                Outcome.Err(VaultError.Crypto.UnsupportedFormatVersion),
                KeyStoreCodec.decode(KeyStoreCodec.encode(KeyStoreBlob(v.format_version + 1, listOf(pw, rec)))),
                "${v.name}: future version",
            )

            // Garbage bytes → malformed.
            assertEquals(
                Outcome.Err(VaultError.Crypto.MalformedContainer),
                KeyStoreCodec.decode(byteArrayOf(0, 1, 2, 3, 4, 5)),
                "${v.name}: malformed cbor",
            )
        }
    }
}
