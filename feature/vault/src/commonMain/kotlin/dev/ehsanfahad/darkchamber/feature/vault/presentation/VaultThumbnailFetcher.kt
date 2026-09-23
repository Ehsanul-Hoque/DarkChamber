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
                    // overload would write the user's decrypted preview to disk.
                    source = ImageSource(Buffer().apply { write(bytes) }, FileSystem.SYSTEM),
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

/** Keys the memory cache by item id, so a re-emitted listing hits instead of re-decrypting. */
internal class VaultThumbnailKeyer : Keyer<VaultThumbnailKey> {
    override fun key(data: VaultThumbnailKey, options: Options): String = data.cacheKey
}
