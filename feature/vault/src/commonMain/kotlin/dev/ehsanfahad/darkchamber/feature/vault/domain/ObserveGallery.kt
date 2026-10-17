package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * What the user has asked of a listing, beyond which items it covers: the order to show them in and
 * the name they are searching for.
 *
 * Separate from [ItemFilter] because the two have different costs. The filter decides **which query
 * runs**, so changing it means a new subscription and a fresh decrypt of every row it returns. These
 * two are applied to a listing that has already arrived, so changing them costs a sort and a string
 * scan over data that is already in memory — see [ObserveGallery].
 */
internal data class GalleryControls(
    val order: GalleryOrder = GalleryOrder.Newest,
    val query: String = "",
) {
    /**
     * A length, never the text (hard rule 4).
     *
     * [query] is a fragment of a vault item's name that the user retyped — decrypted content exactly
     * as the name is — and the generated data-class `toString` would print it into any line that
     * rendered this value. The same redaction `GallerySearch.Open` carries, on the other half of the
     * pair that holds the typed text.
     */
    override fun toString(): String = "GalleryControls(order=$order, query=${query.length} chars)"
}

/**
 * The live gallery listing: the catalog's rows, as tiles, in the order asked for and narrowed to what
 * was searched for.
 *
 * A summary whose metadata failed to open becomes [GalleryItemMeta.Unreadable] — **mapped, never
 * filtered**. A missing tile would quietly hide a tampered row; an unreadable one shows the user
 * that something is wrong while its size, kind and date still render from the catalog's plaintext
 * columns. A *search* is the one thing that may leave such an item out, because a query cannot match
 * a name nobody can read — and it reports how many it left out rather than dropping them silently
 * (see [matching]).
 *
 * **The subscription is keyed by [ItemFilter] alone.** [controls] is combined with the listing rather
 * than selecting it, so changing the order or typing another character re-runs a sort and a filter
 * over items already in memory and never re-subscribes. That distinction is the whole performance
 * design: `VaultObjects.observeItems` unseals and decodes **every row's metadata on every emission**,
 * so a per-keystroke re-subscription would mean a GCM open per row per character typed. Keeping the
 * controls downstream makes that structural rather than a comment — and `ObserveGalleryTest` pins it
 * by counting subscriptions.
 */
internal class ObserveGallery(private val vault: VaultItems) {

    operator fun invoke(
        filter: ItemFilter = ItemFilter.All,
        controls: Flow<GalleryControls> = flowOf(GalleryControls()),
    ): Flow<GalleryListing> =
        if (filter == ItemFilter.All) {
            // The album listing is subscribed **only here**, because only this listing mixes filed
            // and unfiled items. Inside an album every item is in that album and in Unfiled none has
            // one, so those screens would pay a second subscription to label nothing.
            combine(vault.observe(filter), vault.observeAlbums(), controls) { summaries, albums, asked ->
                val names = albums.associate { album -> album.id.toList() to album.name.toTileName() }
                summaries.present(asked) { summary -> summary.albumId?.let { names[it.toList()] } }
            }
        } else {
            combine(vault.observe(filter), controls) { summaries, asked ->
                summaries.present(asked) { null }
            }
        }

    private fun List<VaultItemSummary>.present(
        asked: GalleryControls,
        albumOf: (VaultItemSummary) -> AlbumTileName?,
    ): GalleryListing =
        map { summary -> summary.toItem(albumOf(summary)) }
            .ordered(asked.order)
            // Ordering first, then filtering: `matching` preserves the order it is handed, so the
            // results of a search are in the order the user chose rather than the catalog's.
            .matching(asked.query)

    private fun VaultItemSummary.toItem(album: AlbumTileName?): GalleryItem =
        GalleryItem(
            id = id,
            kind = kind,
            size = size,
            createdAt = createdAt,
            meta = when (val meta = meta) {
                is Outcome.Ok -> GalleryItemMeta.Readable(meta.value.name, meta.value.mime)
                is Outcome.Err -> GalleryItemMeta.Unreadable
            },
            album = album,
        )
}

// `List<Byte>` as the map key, because `ByteArray` hashes by identity — two arrays holding the same
// id would be different keys, so every lookup would miss and every filed item would look unfiled.
//
// Deliberately not the hex key the screens use for list identity: that helper belongs to the
// presentation layer, and the dependency only runs the other way (docs/engineering/MODULES.md §3).
private fun Outcome<String>.toTileName(): AlbumTileName =
    when (this) {
        is Outcome.Ok -> AlbumTileName.Readable(value)
        is Outcome.Err -> AlbumTileName.Unreadable
    }
