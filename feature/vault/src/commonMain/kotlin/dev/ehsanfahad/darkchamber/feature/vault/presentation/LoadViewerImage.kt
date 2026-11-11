package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import dev.ehsanfahad.darkchamber.feature.vault.domain.VaultItems
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.readTo

/**
 * The long edge, in pixels, to decode an image at: twice the viewport's long edge. Sharp at fit and
 * through the first zoom steps, while a 48 MP photo still decodes to a fraction of its full size —
 * the full resolution would need tiling, which this viewer does not do. The **long** edge, so turning
 * the phone does not change what is decoded.
 */
internal fun decodeTarget(viewportWidthPx: Int, viewportHeightPx: Int): Int {
    require(viewportWidthPx > 0 && viewportHeightPx > 0) { "the viewport has not been measured" }
    return maxOf(viewportWidthPx, viewportHeightPx) * 2
}

/** What loading one viewer page came to: a picture, or a page that says why there is none. */
internal sealed interface LoadedPage<out T> {
    /** Not a data class: its one field is a picture of vault content, and has no business in a log line. */
    class Shown<T>(val image: T) : LoadedPage<T>

    data class Page(val page: ViewerPage) : LoadedPage<Nothing>
}

/**
 * Load one item for the viewer: read the whole object into memory, and decode it only once the
 * vault has verified all of it.
 *
 * - **Nothing is read** for a kind with no viewer yet, or for anything over [cap] — the size is a
 *   plaintext column, so refusing costs no decrypt ([openPlan]).
 * - **Decoded only after the read returns `Ok`.** The vault hands each chunk over as soon as that
 *   chunk verifies and checks the whole object's digest last, so before `Ok` the buffer may hold the
 *   start of a tampered or truncated file. On `Err` it is dropped and the page says why
 *   ([pageForOpenFailure]); nothing of it is drawn.
 * - **Our copy is zeroed on every path** — decoded, declined, failed, thrown or cancelled — once the
 *   decoder has made its own. The decoder's internal buffers, and the vault's on the way here, are
 *   outside this code's reach and are not claimed.
 * - **One load at a time.** Each holds a buffer the size of its file and then a decoded picture, and
 *   the viewer asks for three pages at once; queued, the peak is one file rather than three. A load
 *   waiting its turn has allocated nothing.
 * - **Running out of memory is a page, not a crash.** An image within the cap can still be more than
 *   the heap has free; the allocation or the decode failing that way shows [ViewerPage.TooLarge].
 * - **A cancelled load publishes nothing.** A lock cancels the viewer's work and then closes the
 *   session; a blocking read already under way runs to its end, and what it read is dropped
 *   undecoded.
 *
 * The buffer is sized from the catalog's plaintext size and refuses a byte more, so a row that
 * disagrees with its object cannot grow memory past the cap; a disagreement either way is
 * [ViewerPage.CouldntOpen].
 *
 * Generic in the picture type so the rules are tested without a platform bitmap: production passes
 * [dev.ehsanfahad.darkchamber.feature.vault.api.ViewerImageDecoder.decode]. [newBuffer] exists for
 * the tests, which have no other way to see a buffer the decoder never received.
 */
internal class LoadViewerImage<T>(
    private val items: VaultItems,
    private val decode: (bytes: ByteArray, targetLongEdge: Int) -> T?,
    private val io: CoroutineContext,
    private val cap: Long = IMAGE_CAP,
    private val newBuffer: (size: Int) -> ByteArray = { ByteArray(it) },
) {
    suspend fun load(item: GalleryItem, targetLongEdge: Int): LoadedPage<T> {
        when (val plan = openPlan(item.meta, item.size, cap)) {
            is OpenPlan.Show -> return LoadedPage.Page(plan.page)
            OpenPlan.Decode -> Unit
        }
        return oneAtATime.withLock { withContext(io) { readAndDecode(item, targetLongEdge) } }
    }

    private val oneAtATime = Mutex()

    private fun CoroutineScope.readAndDecode(item: GalleryItem, targetLongEdge: Int): LoadedPage<T> {
        val buffer = try {
            newBuffer(item.size.toInt())
        } catch (_: OutOfMemoryError) {
            return LoadedPage.Page(ViewerPage.TooLarge)
        }
        try {
            val sink = ArraySink(buffer)
            val read = try {
                items.open(item.id, sink)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The vault answers with outcomes; a throw is a session closed under the read,
                // or a "never happens". Either way the page names no cause it cannot know.
                Outcome.Err(VaultError.Unexpected(e))
            }
            return when (read) {
                is Outcome.Err -> LoadedPage.Page(pageForOpenFailure(read.error))
                is Outcome.Ok -> if (sink.written != buffer.size) {
                    LoadedPage.Page(ViewerPage.CouldntOpen)
                } else {
                    ensureActive() // a lock during the read: drop it rather than decode a picture nobody will see
                    decodeOrDecline(buffer, targetLongEdge)
                }
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun decodeOrDecline(bytes: ByteArray, targetLongEdge: Int): LoadedPage<T> {
        val image = try {
            decode(bytes, targetLongEdge)
        } catch (_: OutOfMemoryError) {
            return LoadedPage.Page(ViewerPage.TooLarge)
        } catch (e: Exception) {
            null // the seam promises not to throw; one that does has declined, not crashed the viewer
        }
        return if (image != null) LoadedPage.Shown(image) else LoadedPage.Page(ViewerPage.CantShow)
    }

    /** Collects a decrypted object into [into], which is sized for it, and refuses a byte more. */
    private class ArraySink(private val into: ByteArray) : RawSink {
        var written = 0
            private set

        override fun write(source: Buffer, byteCount: Long) {
            if (byteCount > into.size - written) throw IOException("the object is larger than its recorded size")
            val count = byteCount.toInt()
            source.readTo(into, written, written + count)
            written += count
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    companion object {
        /**
         * The largest image read into memory. A starting value, to be confirmed on the smallest heap
         * the app runs on: the passphrase unlock's key derivation needs 64 MiB of the same heap.
         */
        const val IMAGE_CAP: Long = 64L * 1024 * 1024
    }
}
