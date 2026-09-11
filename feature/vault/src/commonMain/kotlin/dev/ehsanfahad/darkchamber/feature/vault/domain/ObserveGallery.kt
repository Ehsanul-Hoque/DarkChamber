package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The live gallery listing, as tiles.
 *
 * A summary whose metadata failed to open becomes [GalleryItemMeta.Unreadable] — **mapped, never
 * filtered**. A missing tile would quietly hide a tampered row; an unreadable one shows the user
 * that something is wrong while its size, kind and date still render from the catalog's plaintext
 * columns.
 */
internal class ObserveGallery(private val vault: VaultItems) {

    operator fun invoke(albumId: ByteArray? = null): Flow<List<GalleryItem>> =
        vault.observe(albumId).map { summaries -> summaries.map(::toItem) }

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
