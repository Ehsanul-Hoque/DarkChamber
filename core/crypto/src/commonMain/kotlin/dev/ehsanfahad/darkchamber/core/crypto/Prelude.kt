package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readShortLe
import kotlinx.io.writeShortLe

/**
 * The 16-byte plaintext prelude of a `.dchm` container (FORMAT.md §2.1). It is not encrypted, but
 * the whole prelude is bound as the header's AAD, so tampering with it fails header authentication.
 */
class Prelude(
    val version: Int,
    val cipherId: Int,
    val headerLen: Int,
    val noncePrefix: ByteArray,
) {
    /** Serialize to the fixed 16-byte layout, little-endian (FORMAT.md §2). */
    fun toByteArray(): ByteArray {
        val buffer = Buffer()
        buffer.write(DchmFormat.MAGIC)
        buffer.writeByte(version.toByte())
        buffer.writeByte(cipherId.toByte())
        buffer.writeShortLe(headerLen.toShort())
        buffer.write(noncePrefix)
        return buffer.readByteArray()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is Prelude &&
                version == other.version &&
                cipherId == other.cipherId &&
                headerLen == other.headerLen &&
                noncePrefix.contentEquals(other.noncePrefix))

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + cipherId
        result = 31 * result + headerLen
        result = 31 * result + noncePrefix.contentHashCode()
        return result
    }

    companion object {
        /**
         * Parse and validate a prelude from the first [DchmFormat.PRELUDE_SIZE] bytes of [bytes].
         * Bad magic or a truncated prelude → [VaultError.Crypto.MalformedContainer]; a version above
         * what we support or an unknown cipher → [VaultError.Crypto.UnsupportedFormatVersion]
         * (the "update the app" path, never confused with a tampering [VaultError.Crypto.IntegrityFailure]).
         */
        fun read(bytes: ByteArray): Outcome<Prelude> {
            if (bytes.size < DchmFormat.PRELUDE_SIZE) {
                return Outcome.Err(VaultError.Crypto.MalformedContainer)
            }
            val buffer = Buffer().apply { write(bytes, 0, DchmFormat.PRELUDE_SIZE) }
            val magic = buffer.readByteArray(DchmFormat.MAGIC.size)
            if (!magic.contentEquals(DchmFormat.MAGIC)) {
                return Outcome.Err(VaultError.Crypto.MalformedContainer)
            }
            val version = buffer.readByte().toInt() and 0xFF
            val cipherId = buffer.readByte().toInt() and 0xFF
            val headerLen = buffer.readShortLe().toInt() and 0xFFFF
            val noncePrefix = buffer.readByteArray(DchmFormat.NONCE_PREFIX_SIZE)
            if ((version to cipherId) !in DchmFormat.SUPPORTED) {
                return Outcome.Err(VaultError.Crypto.UnsupportedFormatVersion)
            }
            return Outcome.Ok(Prelude(version, cipherId, headerLen, noncePrefix))
        }
    }
}
