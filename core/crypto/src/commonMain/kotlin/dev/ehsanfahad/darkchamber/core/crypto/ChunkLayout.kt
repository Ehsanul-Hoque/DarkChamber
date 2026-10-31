package dev.ehsanfahad.darkchamber.core.crypto

/**
 * Where each chunk of a `.dchm` container sits (FORMAT.md §2): the 16-byte prelude, then
 * [headerLen] bytes of sealed header, then the chunks back to back, each its ciphertext followed by a
 * 16-byte GCM tag. Every chunk but the last holds [chunkSize] bytes of plaintext; the last holds the
 * rest, and an empty object is exactly one empty chunk (§2.3).
 *
 * Pure arithmetic over three numbers. Reading a chunk by position needs nothing else.
 */
class ChunkLayout internal constructor(
    val headerLen: Int,
    val chunkSize: Int,
    val plaintextSize: Long,
) {
    init {
        require(chunkSize > 0) { "chunk size must be positive" }
        require(plaintextSize >= 0) { "plaintext size must not be negative" }
        require(headerLen in 0..DchmFormat.MAX_HEADER_LEN) { "header length out of range" }
    }

    /** How many chunks the container holds — at least one. */
    val chunkCount: Long = chunkCountFor(plaintextSize, chunkSize)

    /** The byte offset in the container where chunk [index] starts. */
    fun chunkOffset(index: Long): Long {
        checkIndex(index)
        return DchmFormat.PRELUDE_SIZE + headerLen + index * (chunkSize.toLong() + DchmFormat.GCM_TAG_SIZE)
    }

    /** How many bytes chunk [index] occupies in the container, its tag included. */
    fun storedChunkLength(index: Long): Int {
        checkIndex(index)
        return plaintextChunkLength(index) + DchmFormat.GCM_TAG_SIZE
    }

    /** The container's exact length; anything shorter is truncated and anything longer was added to. */
    val containerLength: Long =
        DchmFormat.PRELUDE_SIZE + headerLen + plaintextSize + chunkCount * DchmFormat.GCM_TAG_SIZE

    private fun plaintextChunkLength(index: Long): Int =
        if (index < chunkCount - 1) chunkSize else (plaintextSize - index * chunkSize).toInt()

    private fun checkIndex(index: Long) {
        require(index in 0 until chunkCount) { "chunk index out of range" }
    }
}
