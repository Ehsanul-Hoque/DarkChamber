package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraDestination
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What the camera's bar says about where photos are going: a title, a live count, and — for an album
 * — whether it still exists.
 *
 * **One subscription per destination, and nothing per item.** An album's name and count both come
 * from the album listing (one small decrypt per album); the whole vault and the unfiled items read
 * the catalog's plaintext count. [CaptureVault] offers no item listing at all, so a count taken by
 * opening every row's metadata cannot be written here by accident.
 */
internal class ObserveDestination(private val vault: CaptureVault) {

    operator fun invoke(destination: CameraDestination): Flow<DestinationInfo> =
        when (destination) {
            CameraDestination.AllItems -> counted(ItemFilter.All, DestinationTitle.AllItems)
            CameraDestination.Unfiled -> counted(ItemFilter.Unfiled, DestinationTitle.Unfiled)
            is CameraDestination.Album -> album(destination.id)
        }.distinctUntilChanged()

    /**
     * A listing that fails to read keeps its title and loses its count: zero would claim the
     * destination is empty, and nothing is known about that.
     */
    private fun counted(filter: ItemFilter, title: DestinationTitle): Flow<DestinationInfo> =
        vault.observeItemCount(filter)
            .map { DestinationInfo(title, it) }
            .catch { emit(DestinationInfo(title, count = null)) }

    private fun album(id: ByteArray): Flow<DestinationInfo> =
        vault.observeAlbums()
            .map { albums ->
                val album = albums.firstOrNull { it.id.contentEquals(id) }
                    ?: return@map DestinationInfo(DestinationTitle.AlbumGone, count = null)
                val title = when (val name = album.name) {
                    is Outcome.Ok -> DestinationTitle.Album(name.value)
                    is Outcome.Err -> DestinationTitle.UnreadableAlbum
                }
                DestinationInfo(title, album.itemCount.toLong())
            }
            // Nothing about the album can be said, its name included — not even that it is gone.
            .catch { emit(DestinationInfo(DestinationTitle.Unknown, count = null)) }
}

/**
 * The destination as the bar draws it. [count] is `null` whenever it is not known — not yet loaded,
 * or unreadable — and never stands in for zero.
 */
internal data class DestinationInfo(val title: DestinationTitle, val count: Long?)

/** What the bar's title says. The words are the formatter's; this is only which case it is. */
internal sealed interface DestinationTitle {
    data object AllItems : DestinationTitle

    data object Unfiled : DestinationTitle

    /**
     * An album whose name opened. [name] is decrypted content: compared by value, printed never
     * (hard rule #4).
     */
    class Album(val name: String) : DestinationTitle {
        override fun equals(other: Any?): Boolean = other is Album && name == other.name

        override fun hashCode(): Int = name.hashCode()

        override fun toString(): String = "Album(name=${name.length} chars)"
    }

    /** An album that is still there but whose name will not open. */
    data object UnreadableAlbum : DestinationTitle

    /** The album the camera files into was deleted while the camera was open. */
    data object AlbumGone : DestinationTitle

    /** The album listing itself could not be read. */
    data object Unknown : DestinationTitle
}
