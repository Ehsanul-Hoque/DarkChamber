package dev.ehsanfahad.darkchamber.feature.vault.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One album, watched: what it is called, and whether it is still there.
 *
 * Both halves are needed by the screen showing an album's contents. The name is its heading and the
 * starting point for a rename. Presence is the harder one: an album deleted while being looked at
 * does not empty the item listing in any way that screen can recognise — the items are unfiled, so
 * the listing goes empty, which is exactly what an album that was *always* empty looks like. Without
 * this, deleting an album from another screen would leave someone on a grid that will never fill and
 * never explain itself.
 *
 * Derived from the album listing rather than a query of its own: that listing already re-emits on
 * every catalog change, and one album's name is one small decrypt.
 *
 * **[distinctUntilChanged] is load-bearing, not tidiness.** The listing re-emits whenever *items*
 * change too — every import, every assignment — and each of those carries a new count and possibly a
 * new cover while the name and the presence are untouched. Without it, every import would republish
 * an identical heading and, worse, step on a rename dialog's draft state mid-word.
 */
internal class ObserveAlbum(private val vault: VaultItems) {

    operator fun invoke(id: ByteArray): Flow<AlbumPresence> =
        vault.observeAlbums()
            .map { albums ->
                val found = albums.firstOrNull { it.id.contentEquals(id) }
                    ?: return@map AlbumPresence.Gone
                AlbumPresence.Present(
                    name = when (val name = found.name) {
                        is Outcome.Ok -> AlbumTileName.Readable(name.value)
                        // Mapped, never treated as absence: an album whose name will not open is
                        // still an album, still holds its items, and must still be deletable.
                        is Outcome.Err -> AlbumTileName.Unreadable
                    },
                )
            }
            .distinctUntilChanged()
}

/** Whether the album being looked at is still in the catalog, and what it is called if so. */
internal sealed interface AlbumPresence {
    data class Present(val name: AlbumTileName) : AlbumPresence

    /** No row with that id. Deleted here, deleted elsewhere, or never there at all. */
    data object Gone : AlbumPresence
}
