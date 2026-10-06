package dev.ehsanfahad.darkchamber.feature.vault.presentation

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.domain.VaultItems
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Sink

/**
 * A filesystem that will not be written to.
 *
 * `ImageSource` holds one so that a decoder asking for a **path** can be served: it spills the
 * buffer to a temp file and hands that over. The buffer here is a decrypted preview, and the spill
 * lands in the system temp directory — app-private, but not the vault's `tmp/`, so the purge on lock
 * never reaches it (hard rule #1, which says *never*, not *briefly*).
 *
 * No decoder this loader is built with asks: the Android path uses `fileOrNull()`, the Skia one
 * reads the buffered source. So this costs nothing today, and turns the day someone adds a decoder
 * that does ask from a silent plaintext write into a preview that fails to load.
 */
private val NoSpill = object : ForwardingFileSystem(FileSystem.SYSTEM) {
    override fun sink(file: Path, mustCreate: Boolean): Sink =
        throw IOException("a decrypted preview may not be written to disk")

    override fun appendingSink(file: Path, mustExist: Boolean): Sink =
        throw IOException("a decrypted preview may not be written to disk")
}

/**
 * What the gallery asks the image loader for: one vault item's preview, identified by its id.
 *
 * A class rather than the raw `ByteArray` because the loader's memory cache is keyed off the model.
 * Array identity would miss on every scroll — each re-emission of the listing builds fresh id
 * arrays — so equality, hash and [cacheKey] are all content-based.
 */
class VaultThumbnailKey(val id: ByteArray) {
    /** Stable, printable, and content-derived: the memory-cache key and the only loggable form. */
    val cacheKey: String = id.joinToString(separator = "") { byte ->
        val value = byte.toInt() and 0xFF
        HEX[value shr 4].toString() + HEX[value and 0x0F]
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is VaultThumbnailKey && id.contentEquals(other.id))

    override fun hashCode(): Int = id.contentHashCode()

    /** An id and nothing else — no name, no bytes (hard rule #4). */
    override fun toString(): String = "VaultThumbnail($cacheKey)"

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}

/**
 * Raised when a stored preview will not open. Carries the typed [error] so the cause survives, and
 * nothing else: an id's **size** and an error code are the most this may say, because it lands in
 * whatever the image loader logs (hard rule #4).
 */
class VaultThumbnailException(id: ByteArray, val error: VaultError) :
    RuntimeException("vault thumbnail unavailable (id ${id.size} bytes): ${error::class.simpleName}")

/**
 * Reads a preview out of the encrypted store for the image loader: decrypt into memory, hand Coil
 * the bytes. Nothing here touches the filesystem — the bytes go into an in-memory buffer and the
 * loader's disk cache is off — so no decrypted preview reaches storage (hard rule #1).
 *
 * **Absent and damaged stay different things.** `null` means the item has no preview, which is the
 * ordinary state for most of a vault and leaves the tile on its type badge. A preview that fails
 * its integrity tag throws [VaultThumbnailException] instead, because quietly falling back would
 * render tampering as though it were an unsupported file type.
 */
internal class VaultThumbnailFetcher(
    private val key: VaultThumbnailKey,
    private val items: VaultItems,
) : Fetcher {

    override suspend fun fetch(): FetchResult? =
        when (val outcome = items.thumbnail(key.id)) {
            is Outcome.Err -> throw VaultThumbnailException(key.id, outcome.error)
            is Outcome.Ok -> outcome.value?.let { bytes ->
                SourceFetchResult(
                    // An in-memory buffer, never a file: ImageSource also accepts a path, and that
                    // overload would write the user's decrypted preview to disk. [NoSpill] closes
                    // the other route to the same place.
                    source = ImageSource(Buffer().apply { write(bytes) }, NoSpill),
                    // Unknown on purpose — the encoders pick JPEG or PNG per image, and Coil sniffs
                    // the bytes anyway. Claiming a type we did not record would be a guess.
                    mimeType = null,
                    dataSource = DataSource.MEMORY,
                )
            }
        }

    class Factory(private val items: VaultItems) : Fetcher.Factory<VaultThumbnailKey> {
        override fun create(data: VaultThumbnailKey, options: Options, imageLoader: ImageLoader): Fetcher =
            VaultThumbnailFetcher(data, items)
    }
}

/**
 * Whether a preview that failed to load failed because its **bytes did not verify**.
 *
 * The loader's error slot fires for anything: an unsupported image, a decoder that gave up, a
 * preview whose GCM tag did not match. Only the last is a tampering signal, and the error-handling
 * rule is explicit that an integrity failure is never quietly rendered as something else — while
 * marking an intact item damaged because a decoder disliked its bytes would be the opposite lie.
 *
 * So the discrimination is exact: this module's fetcher is the only thing that throws
 * [VaultThumbnailException], and only for an `Err` the vault itself reported.
 */
internal fun previewFailedIntegrity(cause: Throwable?): Boolean =
    (cause as? VaultThumbnailException)?.error is VaultError.Crypto.IntegrityFailure

/** Keys the memory cache by item id, so a re-emitted listing hits instead of re-decrypting. */
internal class VaultThumbnailKeyer : Keyer<VaultThumbnailKey> {
    override fun key(data: VaultThumbnailKey, options: Options): String = data.cacheKey
}
