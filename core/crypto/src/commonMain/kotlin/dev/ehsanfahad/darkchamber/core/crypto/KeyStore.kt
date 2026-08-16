package dev.ehsanfahad.darkchamber.core.crypto

import kotlinx.serialization.Serializable

/**
 * Which secret unwraps this copy of the MasterKey (FORMAT.md §3.1). Every slot wraps the *same* MK;
 * having several is how a vault offers more than one unlock path.
 */
enum class Slot {
    /** The user's passphrase, stretched by a memory-hard KDF. */
    PASSPHRASE,

    /** The 256-bit recovery key — the only path on a fresh device (backup restore). */
    RECOVERY,

    /** A hardware-keystore-held key (device-local, never in backups). Reserved for cycle 2c. */
    HARDWARE,
}

/**
 * One wrapped copy of the MasterKey. [wrappedMk] is `AES-256-GCM(deriveWrapKey(secret, kdfParams), MK)`
 * over the [nonce], with the wrap's own parameters bound as AAD (built in TASK-006). [kdfId] duplicates
 * `kdfParams.kdfId` on purpose: it lets a reader recognise an unsupported/reserved KDF (e.g. Argon2id
 * before cycle 2c wires it) and answer `UnsupportedFormatVersion`, without needing that KDF's params
 * type to exist yet.
 */
@Serializable
class WrapEntry(
    val slot: Slot,
    val kdfId: KdfId,
    val kdfParams: KdfParams,
    val nonce: ByteArray,
    val wrappedMk: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is WrapEntry &&
                slot == other.slot &&
                kdfId == other.kdfId &&
                kdfParams == other.kdfParams &&
                nonce.contentEquals(other.nonce) &&
                wrappedMk.contentEquals(other.wrappedMk))

    override fun hashCode(): Int {
        var result = slot.hashCode()
        result = 31 * result + kdfId.hashCode()
        result = 31 * result + kdfParams.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + wrappedMk.contentHashCode()
        return result
    }
}

/**
 * The KeyStore blob (FORMAT.md §3.1): the bootstrap root of the vault, holding every wrap of the
 * MasterKey. Serialized as CBOR ([KeyStoreCodec]); persisted verbatim to `keys/mk.wrapped` by the
 * storage layer. Never a `.dchm`-under-MK — you need this to *obtain* MK.
 */
@Serializable
class KeyStoreBlob(
    val formatVersion: Int,
    val wraps: List<WrapEntry>,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is KeyStoreBlob && formatVersion == other.formatVersion && wraps == other.wraps)

    override fun hashCode(): Int = 31 * formatVersion + wraps.hashCode()
}
