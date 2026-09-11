package dev.ehsanfahad.darkchamber.core.vault

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource

/**
 * Raised when a read is attempted after the vault session has been locked. Internal: it never
 * escapes `VaultObjects`, which turns it into an `Outcome`.
 */
internal class SessionClosedException : IOException("the vault session was locked mid-operation")

/**
 * A [RawSource] that refuses to keep feeding a stream once the session behind it is gone.
 *
 * Encrypting a large file is a blocking, non-cancellable stretch of work: the codec reads its
 * payload twice, a chunk at a time, and cancelling the coroutine around it cannot interrupt that.
 * On a phone a 200 MB import is tens of seconds — so without a check the app would keep encrypting
 * long after the user locked.
 *
 * Guarding the *source* rather than the codec keeps the check where the loop already is, and needs
 * `core:crypto` to know nothing about sessions. The codec buffers its input, so it pulls from here
 * once per segment refill — kilobytes of work, finer than the chunk size — which is the granularity
 * a lock aborts within. The throw unwinds through the atomic object write, which deletes its temp
 * file, so an aborted import leaves nothing behind.
 *
 * The check is cooperative and advisory — `VaultSession` still enforces its own closed state on
 * every operation, so the worst a race costs is finishing one more chunk.
 */
internal class SessionGuardedSource(
    private val delegate: RawSource,
    private val isOpen: () -> Boolean,
) : RawSource {

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (!isOpen()) throw SessionClosedException()
        return delegate.readAtMostTo(sink, byteCount)
    }

    override fun close() = delegate.close()
}
