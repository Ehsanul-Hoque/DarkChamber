package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readTo

/**
 * A sink filling [into] — sized from an item's stored size — and nothing else: a write past its end
 * throws before a byte is copied (`readTo` refuses a range outside the array), so a file longer than its
 * stored size is refused rather than cut short, and [full] says whether it was filled to the last byte.
 * The caller owns [into] and zeroes it.
 */
internal class ExactArraySink(
    private val into: ByteArray,
) : RawSink {
    private var filled = 0

    /** Whether every byte of [into] was written. */
    val full: Boolean get() = filled == into.size

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        val end = filled + byteCount.toInt()
        source.readTo(into, filled, end)
        filled = end
    }

    override fun flush() = Unit

    /** The caller owns [into]; closing this does not touch it. */
    override fun close() = Unit
}
