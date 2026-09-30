package dev.ehsanfahad.darkchamber.core.vault

import kotlinx.io.Buffer
import kotlinx.io.RawSource

/**
 * A [RawSource] that reports how many bytes have been pulled through it, cumulatively.
 *
 * The counter lives **outside** the source, in the import that opened it, because the `.dchm` codec
 * reads its payload twice (size + digest, then the chunk encrypt) from two separately-opened
 * sources. A counter owned by the source would restart on the second open, and the progress the
 * user sees would fill, empty and fill again.
 *
 * The scope that must not restart is **one [ImportPhase]**, not one import. Across the codec's two
 * passes the count runs on, because those two passes are one bar filling once. Across a phase
 * boundary it starts again, because the next phase divides by a different number — a count carried
 * over would put the encrypt phase at the payload size before it had encrypted anything. So the
 * owner of a counter is whichever phase opened the source, and two phases never share one.
 *
 * Counting the source rather than the codec keeps `core:crypto` free of progress concepts and puts
 * the count at the finest granularity available — one buffered refill, well below the chunk size —
 * so a long file moves continuously rather than a chunk at a time.
 */
internal class ProgressSource(
    private val delegate: RawSource,
    private val onRead: (Long) -> Unit,
) : RawSource {

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val n = delegate.readAtMostTo(sink, byteCount)
        if (n > 0L) onRead(n) // -1 is end-of-stream, not bytes
        return n
    }

    override fun close() = delegate.close()
}
