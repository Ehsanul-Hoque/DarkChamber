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

    /**
     * Open the items that are in no album.
     *
     * **Unfiled is not an album.** It has no row, no id, no name and no cover; it is a view of the
     * items that have not been filed. So it is its own intent rather than an [openAlbum] with a
     * reserved id — an id would be a lie the rest of the surface would have to keep, and it is the
     * reason nothing here can rename or delete it.
     */
    fun openUnfiled()

    /**
     * Open the name-entry dialog for a new album.
     *
     * Called again while it is already open this **keeps what was typed** rather than resetting it:
     * the second call can only be a stray tap on an affordance the dialog is covering, and throwing
     * away a half-typed name is a worse answer to it than doing nothing.
     */
    fun startCreate()

    /** What the user has typed so far. Clears a shown [AlbumDraftProblem] — they are fixing it. */
    fun editAlbumName(name: String)

    /**
     * Create the album from the current draft.
     *
     * Ignored while one is already being created, so a double tap makes one album rather than two.
     * Nothing else would catch that: duplicate names are allowed by design, so two albums called
     * "Holiday" is a state the vault is perfectly happy with.
     */
    fun confirmCreate()

    /** Abandon the draft. Ignored while a create is in flight — that one cannot be called back. */
    fun cancelCreate()

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
    /** The new-album draft, present only while the dialog is open. */
    val draft: AlbumDraft? = null,
    /**
     * The album listing could not be read, as a lasting fact rather than as a banner.
     *
     * Separate from [error] because the two have different lifetimes: the banner is dismissible, while
     * an empty [albums] that is empty for want of having been read stays that way — a catalog failure
     * ends the flow underneath it. Dismissing the banner without this would put "No albums yet" back on
     * screen, which is an absence nobody established.
     */
    val listingUnreadable: Boolean = false,
)

/**
 * An album name being typed — a new album's here, or a rename's on the screen showing one album.
 *
 * The typed [name] lives in state rather than in the composable because the problem shown next to it
 * has to clear as it is edited, and two owners of one field is how that ends up stale. Like every
 * other album name on this screen it is plaintext in memory and nowhere else — it dies with the
 * unlocked subtree, and no log line ever carries it (hard rule 4).
 */
data class AlbumDraft(
    val name: String = "",
    val problem: AlbumDraftProblem? = null,
    /** True only while the create is actually running: it disables the dialog and guards a second tap. */
    val saving: Boolean = false,
) {
    /**
     * Redacted like every other type in this package that holds a name: [name] is an album name, so
     * the generated `toString` would print it — and this value is reachable from `AlbumsState` and
     * from `AlbumState.Present`, so printing either of those would carry it too (hard rule #4). The
     * length is withheld as well, since the length of a name is a fact about it.
     */
    override fun toString(): String =
        "AlbumDraft(name=<redacted>, problem=$problem, saving=$saving)"
}

/** Why a draft has not become an album. Stated next to the field, not as a banner over the grid. */
enum class AlbumDraftProblem {
    /** Nothing usable was typed. Nothing was attempted, so there is nothing to undo. */
    NameMissing,

    /** The vault refused the create. No album was made, and the typed name is still there to retry. */
    CreateFailed,

    /**
     * The vault refused a rename. The album keeps the name it had — which is a different thing to say
     * than [CreateFailed]'s "nothing was created", and the reason this is its own case.
     */
    RenameFailed,
}

/** A recoverable album-surface failure, surfaced as honest copy (hard rule #6). */
enum class AlbumsError {
    /** The listing itself failed; the vault is open but its catalog could not be read. */
    ListingFailed,
}
