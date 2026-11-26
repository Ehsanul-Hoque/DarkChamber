package dev.ehsanfahad.darkchamber.app.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import java.io.IOException

/**
 * One vault object, shared by every [DataSource] the player opens over it.
 *
 * - **Reads are serialised.** A [RangeReader] serves one caller, and a player may open more than one
 *   source over the same item; one lock puts every read through it one at a time.
 * - **The reader's first failure is kept**, so when the player stops on an error, the error can be
 *   traced back to the read that caused it — a failed integrity check is the vault's verdict, not the
 *   decoder's — rather than guessed from the player's own message.
 * - **Closed once**, by whoever owns the player, when it is let go. A source closing at the end of a
 *   load does not close the reader: the player opens a new one for every seek.
 */
@UnstableApi
internal class SharedRangeReader(
    private val reader: RangeReader,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false

    /** The first error a read came back with, or `null` while every read has succeeded. */
    @Volatile
    var failure: VaultError? = null
        private set

    val length: Long get() = reader.length

    /** The reader's count of chunks decrypted — a number for the playback measurement. */
    val chunksDecrypted: Long get() = reader.chunksDecrypted

    /**
     * Up to [size] bytes from [position] into [into], or [RangeReader.END]. A refused read throws
     * [VaultReadException] — the [IOException] a data source reports — after keeping its error; a read
     * after [close] throws without keeping anything, since nothing failed: the player was let go.
     */
    fun read(
        position: Long,
        into: ByteArray,
        offset: Int,
        size: Int,
    ): Int =
        synchronized(lock) {
            if (closed) throw IOException("the player was let go")
            when (val read = reader.readAt(position, into, offset, size)) {
                is Outcome.Ok -> {
                    read.value
                }

                is Outcome.Err -> {
                    if (failure == null) failure = read.error
                    throw VaultReadException(read.error)
                }
            }
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            reader.close()
        }
    }

    /** Makes the player's data sources, all reading through this one reader. */
    fun factory(): DataSource.Factory = DataSource.Factory { VaultDataSource(this) }
}

/** A read the vault refused, carrying the vault's own [error]. */
internal class VaultReadException(
    val error: VaultError,
) : IOException("the vault object could not be read")

/**
 * A Media3 [DataSource] over a [SharedRangeReader]: the player asks for a byte range of the item and
 * gets its plaintext, decrypted chunk by chunk in memory. Nothing is cached and nothing is written.
 */
@UnstableApi
internal class VaultDataSource(
    private val shared: SharedRangeReader,
) : BaseDataSource(/* isNetwork = */ false) {
    private var uri: Uri? = null
    private var opened = false
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val available = start(dataSpec.position, dataSpec.length)
        opened = true
        transferStarted(dataSpec)
        return available
    }

    /**
     * Where this open starts and how much it may read: [length] bytes from [position], or to the end
     * when [length] is [C.LENGTH_UNSET], never past the object's end. A start past the end is the
     * position-out-of-range error the player expects.
     */
    internal fun start(
        position: Long,
        length: Long,
    ): Long {
        val total = shared.length
        if (position < 0 || position > total) throw DataSourceException(OUT_OF_RANGE)
        this.position = position
        val toEnd = total - position
        remaining = if (length == C.LENGTH_UNSET.toLong()) toEnd else minOf(length, toEnd)
        return remaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = shared.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n == RangeReader.END) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private companion object {
        const val OUT_OF_RANGE = PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
    }
}
