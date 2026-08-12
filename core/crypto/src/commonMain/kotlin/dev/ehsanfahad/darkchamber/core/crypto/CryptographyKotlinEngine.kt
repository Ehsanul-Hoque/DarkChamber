package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom

/**
 * Production [CryptoEngine] backed by cryptography-kotlin (`cryptography-provider-optimal`).
 *
 * **This is the ONLY file in the codebase permitted to import `dev.whyoleg.cryptography.*`**
 * (hard rule #2). It uses the explicit-IV, blocking AES-256-GCM API so the caller controls the
 * nonce exactly as FORMAT.md requires (the default GCM API generates a random IV — unusable here).
 */
class CryptographyKotlinEngine(
    provider: CryptographyProvider = CryptographyProvider.Default,
) : CryptoEngine {
    private val gcm = provider.get(AES.GCM)
    private val sha256 = provider.get(SHA256)

    private fun keyFrom(raw: ByteArray): AES.GCM.Key =
        gcm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, raw)

    // Explicit-IV GCM is @DelicateCryptographyApi because reusing a (key, IV) pair is catastrophic.
    // We opt in deliberately: FORMAT.md §2.3 guarantees nonce uniqueness (fresh per-object prefix +
    // per-chunk counter, header counter reserved), which is the whole reason we must control the IV.
    // cryptography-kotlin's explicit-IV API is (iv, data, associatedData) — data before AAD.
    @OptIn(DelicateCryptographyApi::class)
    override fun aeadSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        keyFrom(key).cipher().encryptWithIvBlocking(nonce, plaintext, aad)

    @OptIn(DelicateCryptographyApi::class)
    override fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): Outcome<ByteArray> =
        try {
            Outcome.Ok(keyFrom(key).cipher().decryptWithIvBlocking(nonce, ciphertext, aad))
        } catch (_: Throwable) {
            // Any failure to authenticate/open an AEAD ciphertext is possible tampering or
            // corruption — surfaced as IntegrityFailure, never retried or partially rendered.
            Outcome.Err(VaultError.Crypto.IntegrityFailure)
        }

    override fun sha256(bytes: ByteArray): ByteArray = sha256.hasher().hashBlocking(bytes)

    override fun newSha256(): IncrementalHash = object : IncrementalHash {
        private val function = sha256.hasher().createHashFunction()

        override fun update(data: ByteArray, startIndex: Int, endIndex: Int) =
            function.update(data, startIndex, endIndex)

        override fun digest(): ByteArray = function.use { it.hashToByteArray() }
    }

    override fun randomBytes(n: Int): ByteArray = CryptographyRandom.Default.nextBytes(n)
}
