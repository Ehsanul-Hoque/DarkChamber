package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.AlbumSummary
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTile
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The live album listing, as tiles — the album counterpart of [ObserveGallery], and deliberately the
 * same shape.
 *
 * An album whose name failed to open becomes [AlbumTileName.Unreadable]: **mapped, never filtered**.
 * A missing album would quietly hide tampering, and it would strand whatever is filed inside it —
 * the items are still there, still counted, and the album is still the only way to reach them as a
 * group.
 *
 * ### Sorted alphabetically, which items are not
 *
 * Items are newest-first because an import you just made belongs at the top. An album list is
 * something you come back to, so it should keep things where you left them — a new album appearing in
 * the middle of the list is what alphabetical means, and that is the point.
 *
 * Sorting happens **here, after the names are decrypted**, because it cannot happen in SQL: the names
 * are opaque blobs to the catalog. That is affordable only because there are few albums and each
 * costs one small decrypt, which is the same reason the album grid is cheap at all.
 */
internal class ObserveAlbums(private val vault: VaultItems) {

    operator fun invoke(): Flow<List<AlbumTile>> =
        vault.observeAlbums().map { summaries -> summaries.map(::toTile).sortedWith(TILE_ORDER) }

    private fun toTile(summary: AlbumSummary): AlbumTile =
        AlbumTile(
            id = summary.id,
            name = when (val name = summary.name) {
                is Outcome.Ok -> AlbumTileName.Readable(name.value)
                is Outcome.Err -> AlbumTileName.Unreadable
            },
            itemCount = summary.itemCount,
            coverItemId = summary.coverItemId,
            createdAt = summary.createdAt,
        )

    private companion object {
        /**
         * Alphabetical, case-insensitively, with the named albums first.
         *
         * **Unreadable names go last, by decision rather than by accident.** They have no name to
         * sort by, so any position is arbitrary — but it has to be the *same* arbitrary position
         * every time or the grid reshuffles on re-emission. Last keeps a damaged album visible
         * without putting it where a user is reading, and it is still there to be deleted.
         *
         * Case-insensitive because a user who names one album "holiday" and another "Receipts" does
         * not expect capitalisation to decide the order. `lowercase()` is locale-independent here,
         * which is the honest limit of what this can do without a collator.
         *
         * Then by date, then by id — because **duplicate names are allowed**, so equal names are an
         * ordinary case rather than a corner, and without a total order SQLite's row order would
         * leak through and the two could swap places between emissions.
         */
        val TILE_ORDER: Comparator<AlbumTile> = compareBy<AlbumTile>(
            { it.name is AlbumTileName.Unreadable },
            { (it.name as? AlbumTileName.Readable)?.value?.lowercase() ?: "" },
            { it.createdAt },
            // `thenComparator`, not `thenComparing`: the latter is a JVM-only extension on
            // Comparator and does not exist for the native targets, where this module also compiles.
        ).thenComparator { left, right -> left.id.compareContent(right.id) }

        /** Lexicographic over the bytes: a total order, so equal-named albums never swap places. */
        private fun ByteArray.compareContent(other: ByteArray): Int {
            val shared = minOf(size, other.size)
            for (i in 0 until shared) {
                // Unsigned: an id is opaque bytes, and signed comparison would order 0x80 before
                // 0x01 — still a total order, but a surprising one to debug.
                val diff = (this[i].toInt() and 0xFF) - (other[i].toInt() and 0xFF)
                if (diff != 0) return diff
            }
            return size - other.size
        }
    }
}
