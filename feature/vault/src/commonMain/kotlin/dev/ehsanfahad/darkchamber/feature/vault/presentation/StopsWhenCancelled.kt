package dev.ehsanfahad.darkchamber.feature.vault.presentation

import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.RawSink

/**
 * A sink for a whole-file read that refuses every write once [reading] — the coroutine doing the read —
 * is no longer active. A vault read is a blocking call that decrypts to the end of the file, and a
 * cancel cannot interrupt it; but every chunk it decrypts is written here, so the first write after a
 * lock or a swipe throws, the decrypt stops within a chunk, and the item's key is let go — rather than
 * the rest of the file being decrypted, with that key live, after the vault said it was locked.
 */
internal class StopsWhenCancelled(private val reading: Job, private val into: RawSink) : RawSink {
    override fun write(source: Buffer, byteCount: Long) {
        // A cancellation, so a caller that sees it rethrows it; the codec, which catches everything,
        // turns it into an error and stops.
        if (!reading.isActive) throw CancellationException("the read was abandoned")
        into.write(source, byteCount)
    }

    override fun flush() = into.flush()

    /** The caller owns [into]; closing this does not close it. */
    override fun close() = Unit
}
