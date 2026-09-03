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
 * Production Argon2id cost for the passphrase slot (FORMAT.md §3): 64 MiB, 3 passes, 2 lanes. Like
 * PBKDF2's count, raised over time by writing larger values into a wrap's [KdfParams.Argon2id] —
 * old wraps keep their stored cost. All three are AAD-bound, so a downgrade fails authentication.
 */
const val ARGON2ID_DEFAULT_MEMORY_KIB = 65536
const val ARGON2ID_DEFAULT_ITERATIONS = 3
const val ARGON2ID_DEFAULT_PARALLELISM = 2

/** Argon2 version pinned on every platform (RFC 9106 / Argon2 1.3). Not a stored parameter. */
const val ARGON2ID_VERSION = 0x13

/**
 * Minimum Argon2id salt length (the Argon2 floor, `ARGON2_MIN_SALT_LENGTH` / RFC 9106). A shorter
 * salt from a crafted/corrupt blob is rejected as `MalformedContainer` before it can reach a native
 * precondition. DarkChamber's own wraps use [KDF_SALT_SIZE] (16).
 */
const val ARGON2ID_MIN_SALT_SIZE = 8

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

    /** Argon2id — the memory-hard default passphrase KDF. */
    ARGON2ID,
}

/**
 * Parameters for deriving a wrap key, serialized beside the wrap. The concrete subtype *is* the KDF
 * choice ([kdfId]). Each variant's fields are AAD-bound with its wrap (see [MasterKeyWrap]), so a
 * cost/param downgrade cannot be substituted without breaking authentication.
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

    /**
     * Argon2id cost parameters. The version is not stored — it is pinned to [ARGON2ID_VERSION] on
     * every platform (a stored version would only add a footgun for its one legal value).
     */
    @Serializable
    class Argon2id(
        val salt: ByteArray,
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
    ) : KdfParams {
        override val kdfId get() = KdfId.ARGON2ID

        override fun equals(other: Any?) =
            this === other || (
                other is Argon2id &&
                    memoryKib == other.memoryKib &&
                    iterations == other.iterations &&
                    parallelism == other.parallelism &&
                    salt.contentEquals(other.salt)
            )

        override fun hashCode(): Int {
            var result = salt.contentHashCode()
            result = 31 * result + memoryKib
            result = 31 * result + iterations
            result = 31 * result + parallelism
            return result
        }
    }
}

/**
 * Derives a 32-byte wrap key from a user secret and its stored [KdfParams]. The seam the key
 * hierarchy owns: Argon2id (the default) and PBKDF2 (fallback) for passphrases, HKDF for the
 * recovery key — each selected by the params subtype, no format change to add one. All primitive
 * work goes through [CryptoEngine] (hard rule #2).
 */
interface KeyDerivation {
    fun deriveWrapKey(secret: ByteArray, params: KdfParams): ByteArray
}

class DefaultKeyDerivation(private val engine: CryptoEngine) : KeyDerivation {
    override fun deriveWrapKey(secret: ByteArray, params: KdfParams): ByteArray =
        when (params) {
            is KdfParams.Pbkdf2 -> engine.pbkdf2Sha256(secret, params.salt, params.iterations, WRAP_KEY_SIZE)
            is KdfParams.Hkdf -> engine.hkdfSha256(secret, params.salt, params.info, WRAP_KEY_SIZE)
            is KdfParams.Argon2id ->
                engine.argon2id(secret, params.salt, params.memoryKib, params.iterations, params.parallelism, WRAP_KEY_SIZE)
        }
}
