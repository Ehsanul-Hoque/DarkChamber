package dev.ehsanfahad.darkchamber.core.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * The `.dchm` object header (FORMAT.md §2.2). Serialized as CBOR and then sealed with the object's
 * DEK (the sealing happens in the container codec, not here). `nonce_prefix` is deliberately absent —
 * it lives in the plaintext prelude so the header nonce can be derived before decryption.
 */
@Serializable
class DchmHeader(
    val originalName: String,
    val mime: String,
    val plaintextSize: Long,
    val createdAt: Long,
    val sha256Plaintext: ByteArray,
    val chunkSize: Int,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is DchmHeader &&
                originalName == other.originalName &&
                mime == other.mime &&
                plaintextSize == other.plaintextSize &&
                createdAt == other.createdAt &&
                sha256Plaintext.contentEquals(other.sha256Plaintext) &&
                chunkSize == other.chunkSize)

    override fun hashCode(): Int {
        var result = originalName.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + plaintextSize.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + sha256Plaintext.contentHashCode()
        result = 31 * result + chunkSize
        return result
    }
}

/** CBOR (de)serialization of [DchmHeader] — the on-disk form before/after DEK sealing. */
object DchmHeaderCodec {
    private val cbor = Cbor { }

    fun encode(header: DchmHeader): ByteArray = cbor.encodeToByteArray(DchmHeader.serializer(), header)

    fun decode(bytes: ByteArray): DchmHeader = cbor.decodeFromByteArray(DchmHeader.serializer(), bytes)
}
