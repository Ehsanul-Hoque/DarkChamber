package dev.ehsanfahad.darkchamber.feature.vault.api

import com.arkivanov.decompose.value.Value

/**
 * The album grid: every album with its name, how much is in it, and what represents it.
 *
 * Presentation follows the same style as the gallery (docs/engineering/PATTERNS.md §3) — the
 * component *is* the state holder. It lives inside the unlocked subtree, so locking destroys it
 * along with everything it holds.
 *
 * **Opening an album is navigation, so it is not an intent here.** The host supplies a callback at
 * construction, the way the unlocked subtree is handed its lock callback: a component that pushed
 * screens itself would have to know the shape of the stack it sits in.
 */
interface VaultAlbumsComponent {
    val state: Value<AlbumsState>

    /**
     * Open the album with [id] — which is navigation, so it leaves through the callback the host gave
     * this component rather than being acted on here.
     */
    fun openAlbum(id: ByteArray)

    /** Clear a surfaced [AlbumsError] after the user acknowledges it. */
    fun dismissError()
}

/**
 * What the album grid is showing.
 *
 * [loading] starts `true` and drops on the first catalog emission, so an opening vault shows a
 * spinner rather than flashing "no albums yet" at someone who has several.
 */
data class AlbumsState(
    val albums: List<AlbumTile> = emptyList(),
    val loading: Boolean = true,
    val error: AlbumsError? = null,
)

/** A recoverable album-surface failure, surfaced as honest copy (hard rule #6). */
enum class AlbumsError {
    /** The listing itself failed; the vault is open but its catalog could not be read. */
    ListingFailed,
}
