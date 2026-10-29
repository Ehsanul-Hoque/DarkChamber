package dev.ehsanfahad.darkchamber.core.vault

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * Host/simulator test double for [ThumbnailEncoder], configured to produce one of the four things a
 * real decoder does: succeed, decline, hand back nothing usable, or blow up.
 *
 * Every mode **opens the source, reads it to exhaustion and closes it** before doing anything else —
 * including [Mode.Throw], which throws afterwards. That ordering is deliberate. It means the
 * recorded [lastSource] proves the plaintext actually reached the encoder rather than the fake
 * short-circuiting, and it means a caller that leaks the source on the failure path shows up as an
 * unclosed source instead of being hidden behind a fake that never opened one.
 *
 * @param mode which outcome to produce for every [encode] call.
 * @param thumbnail the bytes [Mode.Encode] returns.
 */
class FakeThumbnailEncoder(
    private val mode: Mode = Mode.Encode,
    private val thumbnail: ByteArray = byteArrayOf(0x54, 0x48, 0x55, 0x4D), // "THUM"
) : ThumbnailEncoder {
    enum class Mode {
        /** A supported image: returns the configured thumbnail bytes. */
        Encode,

        /** An unsupported type, or a decoder that declines — the ordinary `null` outcome. */
        Decline,

        /** Claims success but produces nothing usable (a truncated or zero-byte encode). */
        Empty,

        /** A decoder that blows up on a corrupt file — the caller has to contain it. */
        Throw,
    }

    /** How many times [encode] was called (for asserting the import reads the payload exactly once more). */
    var encodeCount: Int = 0
        private set

    /** The [mime] of the most recent call. */
    var lastMime: String? = null
        private set

    /** The [maxEdgePx] of the most recent call. */
    var lastMaxEdgePx: Int? = null
        private set

    /** Everything the most recent call read out of its source. */
    var lastSource: ByteArray? = null
        private set

    override fun encode(mime: String, maxEdgePx: Int, openSource: () -> RawSource): ByteArray? {
        encodeCount++
        lastMime = mime
        lastMaxEdgePx = maxEdgePx
        lastSource = openSource().buffered().use { it.readByteArray() }

        return outcome()
    }

    /** How many times the ranged [encode] was called. */
    var rangedCount: Int = 0
        private set

    /** Everything the most recent ranged call read from its reader, up to the end or the first error. */
    var lastRanged: ByteArray? = null
        private set

    /** The first error the most recent ranged call's reader returned, if it returned one. */
    var lastRangedError: dev.ehsanfahad.darkchamber.core.model.VaultError? = null
        private set

    /**
     * Reads the whole object front to back in small, uneven steps, as a frame grabber would not — but
     * reading all of it is what lets a test see every chunk reached. A read error ends the reading and
     * the call returns `null`, as the platform encoders do when their reader fails.
     */
    /** The reader the most recent ranged call was handed — kept so a test can check it was closed after. */
    var lastReader: RangeReader? = null
        private set

    override fun encode(mime: String, maxEdgePx: Int, reader: RangeReader): ByteArray? {
        rangedCount++
        lastReader = reader
        lastMime = mime
        lastMaxEdgePx = maxEdgePx
        lastRangedError = null
        val out = Buffer()
        val step = ByteArray(37)
        var position = 0L
        while (true) {
            when (val read = reader.readAt(position, step, 0, step.size)) {
                is dev.ehsanfahad.darkchamber.core.model.Outcome.Err -> {
                    lastRangedError = read.error
                    lastRanged = out.readByteArray()
                    return null
                }
                is dev.ehsanfahad.darkchamber.core.model.Outcome.Ok -> {
                    if (read.value == RangeReader.END) break
                    out.write(step, 0, read.value)
                    position += read.value
                }
            }
        }
        lastRanged = out.readByteArray()
        return outcome()
    }

    private fun outcome(): ByteArray? = when (mode) {
        Mode.Encode -> thumbnail
        Mode.Decline -> null
        Mode.Empty -> ByteArray(0)
        Mode.Throw -> error("decoder failed")
    }
}
