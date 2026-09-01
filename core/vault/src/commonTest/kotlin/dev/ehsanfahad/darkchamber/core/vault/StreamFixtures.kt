package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.IncrementalHash
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

/**
 * Test fixtures for the bounded-RAM streaming round-trip (5c §5.2): a deterministic generating
 * source and a counting/hashing discard sink. Together they push an arbitrary volume through
 * import + open while only ever holding O(chunkSize) bytes — the plaintext is never materialised
 * whole, so the same code path proves out at 16 MiB in CI and at 2 GiB opt-in on a device.
 */

/** The plaintext byte at absolute position [pos] — a cheap deterministic keystream (position-based,
 *  so a re-opened source reproduces identical bytes for the codec's two-pass read). */
internal fun streamByte(pos: Long): Byte =
    ((pos * 6364136223846793005L + 1442695040888963407L) ushr 33).toByte()

/** Default streaming volume when no override is supplied — 16 MiB (16 chunks at the 1 MiB default). */
internal const val DEFAULT_STREAM_BYTES: Long = 16L * 1024 * 1024

/** The target streaming volume: the `dchm.stream.bytes` system property on host (e.g.
 *  `-Ddchm.stream.bytes=2147483648` for the 2 GiB opt-in run), the default elsewhere. */
expect fun streamTestBytes(): Long

/** A re-openable source that yields [total] deterministic bytes without ever holding them all. */
internal class GeneratingSource(private val total: Long) : RawSource {
    private var pos = 0L
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (pos >= total) return -1L
        val n = minOf(byteCount, total - pos).toInt()
        val arr = ByteArray(n) { i -> streamByte(pos + i) }
        sink.write(arr)
        pos += n
        return n.toLong()
    }

    override fun close() {}
}

/** A sink that counts and SHA-256-hashes everything written, then discards it (bounded memory). */
internal class CountingHashingSink(private val hasher: IncrementalHash) : RawSink {
    var bytes: Long = 0L
        private set

    override fun write(source: Buffer, byteCount: Long) {
        val arr = source.readByteArray(byteCount.toInt())
        hasher.update(arr)
        bytes += arr.size
    }

    override fun flush() {}

    override fun close() {}

    fun digest(): ByteArray = hasher.digest()
}
