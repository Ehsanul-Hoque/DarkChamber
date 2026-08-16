package dev.ehsanfahad.darkchamber.core.crypto

import kotlinx.serialization.Serializable

/** The wrap-key length every KDF in the key hierarchy produces (AES-256 → 32 bytes). */
const val WRAP_KEY_SIZE = 32

/**
 * Production PBKDF2 cost (FORMAT.md §3). OWASP's PBKDF2-HMAC-SHA256 floor; raised over time by
 * writing a larger value into a wrap's [KdfParams.Pbkdf2] — old wraps keep their stored count.
 */
const val PBKDF2_DEFAULT_ITERS = 600_000

/** Default salt length for a passphrase/recovery wrap. */
const val KDF_SALT_SIZE = 16

/** HKDF domain-separation label for the recovery-key wrap slot (FORMAT.md §3). */
const val RECOVERY_HKDF_INFO = "darkchamber:recovery-wrap:v1"

/**
 * Registry of key-derivation functions a [WrapEntry] may name (FORMAT.md §3.1). The id is stored
 * beside each wrap and bound into its AAD, so it cannot be swapped for a weaker one without
 * breaking authentication.
 */
enum class KdfId {
    /** PBKDF2-HMAC-SHA256 — the v1 passphrase KDF. */
    PBKDF2_SHA256,

    /** HKDF-SHA256 — the recovery-key slot (input is already full entropy, no stretching). */
    HKDF_SHA256,

    /** Argon2id — reserved; the passphrase-slot upgrade, wired with platform bindings in cycle 2c. */
    ARGON2ID,
}

/**
 * Parameters for deriving a wrap key, serialized beside the wrap. The concrete subtype *is* the KDF
 * choice ([kdfId]); there is deliberately no `ARGON2ID` variant yet — its parameters land in 2c with
 * the KDF itself, so an Argon2id wrap cannot be constructed before it can be honoured.
 */
@Serializable
sealed interface KdfParams {
    val kdfId: KdfId

    @Serializable
    class Pbkdf2(val salt: ByteArray, val iterations: Int) : KdfParams {
        override val kdfId get() = KdfId.PBKDF2_SHA256

        override fun equals(other: Any?) =
            this === other || (other is Pbkdf2 && iterations == other.iterations && salt.contentEquals(other.salt))

        override fun hashCode() = 31 * salt.contentHashCode() + iterations
    }

    @Serializable
    class Hkdf(val salt: ByteArray, val info: ByteArray) : KdfParams {
        override val kdfId get() = KdfId.HKDF_SHA256

        override fun equals(other: Any?) =
            this === other || (other is Hkdf && salt.contentEquals(other.salt) && info.contentEquals(other.info))

        override fun hashCode() = 31 * salt.contentHashCode() + info.contentHashCode()
    }
}

/**
 * Derives a 32-byte wrap key from a user secret and its stored [KdfParams]. The seam the key
 * hierarchy owns: PBKDF2 today, Argon2id (2c) dropping in behind the same call without any format
 * change. All primitive work goes through [CryptoEngine] (hard rule #2).
 */
interface KeyDerivation {
    fun deriveWrapKey(secret: ByteArray, params: KdfParams): ByteArray
}

class DefaultKeyDerivation(private val engine: CryptoEngine) : KeyDerivation {
    override fun deriveWrapKey(secret: ByteArray, params: KdfParams): ByteArray =
        when (params) {
            is KdfParams.Pbkdf2 -> engine.pbkdf2Sha256(secret, params.salt, params.iterations, WRAP_KEY_SIZE)
            is KdfParams.Hkdf -> engine.hkdfSha256(secret, params.salt, params.info, WRAP_KEY_SIZE)
        }
}
