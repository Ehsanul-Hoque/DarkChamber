package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
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
)

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
        combine(vault.observe(filter), controls) { summaries, asked ->
            summaries.map(::toItem)
                .ordered(asked.order)
                // Ordering first, then filtering: `matching` preserves the order it is handed, so the
                // results of a search are in the order the user chose rather than the catalog's.
                .matching(asked.query)
        }

    private fun toItem(summary: VaultItemSummary): GalleryItem =
        GalleryItem(
            id = summary.id,
            kind = summary.kind,
            size = summary.size,
            createdAt = summary.createdAt,
            meta = when (val meta = summary.meta) {
                is Outcome.Ok -> GalleryItemMeta.Readable(meta.value.name, meta.value.mime)
                is Outcome.Err -> GalleryItemMeta.Unreadable
            },
        )
}
