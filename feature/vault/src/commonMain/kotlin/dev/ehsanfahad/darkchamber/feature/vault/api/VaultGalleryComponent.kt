package dev.ehsanfahad.darkchamber.feature.vault.api

import com.arkivanov.decompose.value.Value
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind

/**
 * The vault gallery: the listing of everything in the open vault, plus the import affordance.
 *
 * Presentation follows the session gate's own style (docs/engineering/PATTERNS.md §3) — the
 * component *is* the state holder, exposing [Value] plus named intents, on a lifecycle-scoped
 * coroutine scope. It exists only inside the unlocked subtree, so locking destroys it along with
 * everything it holds; nothing here needs its own teardown.
 */
interface VaultGalleryComponent {
    val state: Value<GalleryState>

    /**
     * Show the listing in [order]. Changes what is on screen and nothing in the vault.
     *
     * Per-screen and remembered nowhere: the order chosen inside one album is that screen's own, and
     * a preference that outlives the screen belongs with settings rather than with a listing.
     */
    fun chooseOrder(order: GalleryOrder)

    /**
     * Open the search field, empty. Opening is not filtering — the grid is untouched until typing.
     *
     * **Ignored while it is already open**, where this can only be a second tap on the field itself:
     * re-opening would silently discard what has been typed. [closeSearch] is the way to clear it.
     */
    fun openSearch()

    /** What has been typed into it. Ignored while the search is closed. */
    fun editSearch(query: String)

    /** Close the field **and clear the query**, so no filter outlives the control that explains it. */
    fun closeSearch()

    /** Pick photos and videos from the system library. A cancelled picker is a no-op, not an error. */
    fun importPhotos()

    /** Pick documents. Same contract; a different platform picker (see [PhotoPicker]). */
    fun importFiles()

    /** Stop a running batch. What has already been imported stays imported. */
    fun cancelImport()

    /** Dismiss the finished-batch summary. */
    fun acknowledgeImport()

    /** Take the summary's offer to delete the imported originals. Opens the disclosure; deletes nothing. */
    fun requestOriginalDelete()

    /** Delete them, having shown the disclosure. Reports how many the platform actually took. */
    fun confirmOriginalDelete()

    /** Decline the disclosure. The only other way out of it — it cannot be dismissed. */
    fun keepOriginals()

    /**
     * Long-press a tile: enter selecting mode with that item selected, or — already selecting —
     * toggle it, so the caller does not branch on a mode it is already in.
     *
     * **Ignored while a batch is running.** The grid grows underneath an import, so a selection made
     * then is a selection of a moving target.
     */
    fun selectItem(id: ByteArray)

    /** Tap a tile while selecting: add it or remove it. A no-op outside selecting mode. */
    fun toggleItem(id: ByteArray)

    /** Select everything the screen is currently showing — which inside an album is that album. */
    fun selectAllItems()

    /** Leave selecting mode and drop the selection. Clear and back both land here. */
    fun clearSelection()

    /**
     * Open the album chooser for the selected items.
     *
     * Choosing is a step of its own because the destination is a decision, and because the chooser is
     * where "add" is shown for what it is: an item is in at most one album, so the list is a set of
     * places to **move** things to.
     *
     * Ignored with nothing selected, and ignored while a move is already running.
     */
    fun startFiling()

    /** Move the selected items into [albumId]. Only ever reached from the chooser. */
    fun chooseAlbum(albumId: ByteArray)

    /** Abandon the chooser without moving anything. */
    fun cancelFiling()

    /**
     * Take the selected items out of the album they are in — they become unfiled, and **nothing is
     * deleted**. No chooser: there is only one place to go.
     */
    fun unfileSelection()

    /** Open the name field inside the chooser, for a vault with no album to choose yet. */
    fun startFilingCreate()

    /** What has been typed into the chooser's name field. */
    fun editFilingAlbumName(name: String)

    /**
     * Create that album and move the selected items into it, in one step — the point of offering a
     * create here at all is that the user already said where things should go.
     */
    fun confirmFilingCreate()

    /** Close the name field, leaving the chooser open. */
    fun cancelFilingCreate()

    /** Dismiss the "moved" report. */
    fun acknowledgeFiling()

    /**
     * Open the rename dialog for the album being viewed, **prefilled with its current name**: a
     * rename starts from what the thing is called, and an empty field would make the common edit —
     * fixing a typo — a retype.
     *
     * A no-op on any other listing, and on an album whose name would not open: there is nothing to
     * start from, and prefilling an unreadable name with a placeholder would offer to replace a name
     * nobody has seen.
     */
    fun startAlbumRename()

    /** What has been typed into the rename field. Clears a shown problem — they are fixing it. */
    fun editAlbumName(name: String)

    /** Rename the album to the current draft. Ignored while one rename is already running. */
    fun confirmAlbumRename()

    /** Abandon the rename. Ignored while the write is in flight — that cannot be called back. */
    fun cancelAlbumRename()

    /**
     * Ask to delete the album being viewed. **Opens the confirmation; deletes nothing**, because what
     * deleting an album does is the opposite of what users arrive expecting and the confirmation is
     * where that gets corrected.
     */
    fun requestAlbumDelete()

    /** Delete it, the confirmation having been shown. Its items are kept and become unfiled. */
    fun confirmAlbumDelete()

    /** Decline the confirmation. */
    fun cancelAlbumDelete()

    /**
     * Leave an album that is no longer there — the one action offered on that notice.
     *
     * Navigation, so it leaves through the callback the host gave this component, like opening an
     * album does from the grid.
     */
    fun leaveAlbum()

    /**
     * Open the camera on this listing: its photos go where an import from here would, and it counts
     * this listing's items. Navigation, so it leaves through the host's callback. Ignored whenever
     * the button is not on screen — while selecting, during a batch, over a summary.
     */
    fun openCamera()

    /** Clear a surfaced [GalleryError] after the user acknowledges it. */
    fun dismissError()
}

/**
 * What the gallery is showing. Holds **ids and display metadata only** — never decrypted bytes — so
 * a retained state can never pin plaintext past a lock (docs/engineering/PATTERNS.md §3).
 *
 * [loading] starts `true` and drops on the first catalog emission, so an opening vault shows a
 * spinner rather than flashing "no items yet" at someone whose vault is full.
 */
data class GalleryState(
    val items: List<GalleryItem> = emptyList(),
    val loading: Boolean = true,
    /**
     * The order [items] is in. Newest first on arrival, which is the order a vault opens on.
     *
     * Here as well as inside the listing stage because the controls row has to draw which order is
     * current; the two are written together, so neither can show something the grid is not in.
     */
    val order: GalleryOrder = GalleryOrder.Newest,
    /**
     * Whether a name search is running, and what it has found so far.
     *
     * [items] already means what the screen should draw, so a narrowed listing needs nothing else —
     * this is what the *row* draws, and the one place the typed text lives.
     */
    val search: GallerySearch = GallerySearch.Closed,
    val batch: ImportState = ImportState.Idle,
    val error: GalleryError? = null,
    val selection: GallerySelection = GallerySelection(),
    val scope: GalleryScope = GalleryScope.WholeVault,
    /**
     * The album this screen is showing, or `null` on the two listings that are not an album.
     *
     * Separate from [scope] rather than folded into it: the scope is fixed at construction and is what
     * the copy branches on, while this changes underneath the screen — an album can be renamed from
     * here and can disappear from elsewhere.
     */
    val album: AlbumState? = null,
    /** Where the selected items are being moved to, if that is happening. */
    val filing: Filing? = null,
    /**
     * The listing could not be read, as a lasting fact rather than as a banner.
     *
     * Separate from [error] because the two have different lifetimes. [error] is dismissible — the user
     * has seen it and says so — while this stays true until a listing actually arrives, and in practice
     * that is for good: a catalog failure ends the flow underneath, so nothing re-emits afterwards.
     *
     * Without it, dismissing the banner would leave an empty [items] with nothing to distinguish it
     * from a vault that is genuinely empty, and the screen would go back to saying so.
     */
    val listingUnreadable: Boolean = false,
) {
    /** Whether a batch is between the user asking for one and it ending. */
    val importing: Boolean get() = batch is ImportState.Picking || batch is ImportState.Running
}

/**
 * A name search, open or not.
 *
 * Sealed rather than a flag beside a string, for three reasons: there is no "closed but still
 * filtering" state to get wrong, [Open.hiddenUnreadable] exists only where it means something, and
 * the redaction sits on the type that carries the text rather than on everything holding it.
 */
sealed interface GallerySearch {

    /** No field, no filter. The state a listing opens in and returns to. */
    data object Closed : GallerySearch

    /**
     * The field is open, holding [query] — which may be empty, since opening is not filtering.
     *
     * [hiddenUnreadable] is how many items the search had to leave out because their names would
     * not open: a query cannot match a name nobody can read, and dropping such a row silently would
     * let a search conceal the one item worth looking at. It travels with the listing, so it is the
     * count for the items currently being searched rather than a running total.
     */
    data class Open(
        val query: String,
        val hiddenUnreadable: Int = 0,
    ) : GallerySearch {
        /**
         * A length, never the text (hard rule #4).
         *
         * [query] is a fragment of a vault item's name that the user retyped — decrypted content
         * exactly as the name is, and the easiest piece of it to leak, because the generated
         * data-class `toString` would print it into any log line rendering a [GalleryState].
         */
        override fun toString(): String =
            "Open(query=${query.length} chars, hiddenUnreadable=$hiddenUnreadable)"
    }
}

/**
 * Which listing a gallery is showing — **not** the filter itself.
 *
 * It exists because an empty grid means three different things, and saying the wrong one is worse
 * than saying nothing: a vault with no files at all wants to be told how to add some, an album with
 * nothing in it is a perfectly ordinary album, and nothing unfiled means everything has been filed,
 * which is a success rather than an absence.
 *
 * The album's id is deliberately absent. Copy does not need it — an album's name is already on the
 * screen that led here — and a state that carried it would be holding an id for the sake of it.
 */
enum class GalleryScope {
    /** Everything in the vault, filed or not. The listing a vault opens on. */
    WholeVault,

    /** One album's contents. */
    Album,

    /** The items in no album. */
    Unfiled,
}

/**
 * The album an album-scoped gallery is showing.
 *
 * Sealed, so [Gone] cannot be confused with "present but unnamed": the first has no actions at all
 * and the second has all of them. A bag of nullable fields would let a screen offer to rename an
 * album that is not there.
 */
sealed interface AlbumState {

    /**
     * It is there. [rename] and [delete] are the two things that can be in progress on it, and they
     * are separate rather than one "current action" because neither can start while the other is
     * open — the dialogs are modal, so the type does not need to enforce what the UI already does.
     */
    data class Present(
        val name: AlbumTileName,
        val rename: AlbumDraft? = null,
        val delete: AlbumDelete? = null,
    ) : AlbumState

    /**
     * It is not in the catalog any more — deleted here, or from somewhere else while this screen was
     * open.
     *
     * This state exists because an album's disappearance is **invisible** in the item listing: its
     * items are unfiled rather than destroyed, so the grid simply empties, which is indistinguishable
     * from an album that was always empty. Without saying so, a user would be left on a grid that will
     * never fill and never explain why.
     */
    data object Gone : AlbumState
}

/**
 * Moving selected items into an album, or out of one.
 *
 * Sealed, in the same shape as the import's states and for the same reason: the bottom of the screen
 * shows exactly one of these at a time, and a bag of flags would let it show two.
 */
sealed interface Filing {

    /**
     * The chooser is open. [albums] is every album the items could go to — **never the one they are
     * already in**, since moving them there is nothing.
     *
     * [creating] is the name field, present only once asked for. It exists because a vault with no
     * albums would otherwise offer an empty list and no way forward, having just asked the user to
     * choose.
     */
    data class Choosing(
        val albums: List<AlbumChoice> = emptyList(),
        val creating: AlbumDraft? = null,
    ) : Filing

    /** The write is running. Nothing here can be cancelled: it is one statement. */
    data object Moving : Filing

    /**
     * It is done, and this says what actually happened.
     *
     * [moved] can be fewer than [requested]: an item shredded between selecting and moving is skipped
     * rather than failing the batch, and reporting the request as though it were the result would be
     * claiming a move that did not happen.
     */
    data class Moved(
        val requested: Int,
        val moved: Int,
        val destination: FilingDestination,
    ) : Filing
}

/** Which way the items went, which is all the report needs to know to word itself. */
enum class FilingDestination {
    /** Into an album. */
    Album,

    /** Out of one — unfiled, **not** deleted. */
    Unfiled,
}

/**
 * One row in the chooser: an album the selection can be moved to.
 *
 * Deliberately not [AlbumTile] — a chooser row has no cover and no count to draw, and handing it one
 * would invite a tile's worth of decoration onto a list that is answering a question.
 */
class AlbumChoice(val id: ByteArray, val name: AlbumTileName) {
    // Content equality, because [id] is a ByteArray and the generated one would compare it by identity:
    // a chooser list rebuilt from an unchanged catalog would otherwise look like a change.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AlbumChoice) return false
        return id.contentEquals(other.id) && name == other.name
    }

    override fun hashCode(): Int = 31 * id.contentHashCode() + name.hashCode()

    /** An id length and whether the name opened — never the name itself (hard rule #4). */
    override fun toString(): String = "AlbumChoice(id=${id.size}B, readable=${name is AlbumTileName.Readable})"
}

/** How far a delete has got. The confirmation is a step of its own because the copy is the point. */
enum class AlbumDelete {
    /** The confirmation is up and nothing has been written. */
    Confirming,

    /** The two writes are running. Both buttons are spent — this cannot be called back. */
    Deleting,

    /**
     * The vault refused. Deliberately **not** reported as "nothing changed": the vault unfiles the
     * items and then deletes the album row, with no transaction spanning the two, so a failure may
     * have left the items already unfiled.
     */
    Failed,
}

/**
 * What is selected, and whether the screen is in selecting mode.
 *
 * Ids only, like the rest of this state — a selection can never pin decrypted bytes past a lock.
 *
 * Not a data class: the generated `equals` would compare the [ids] arrays by identity, so two
 * selections of the same items would be unequal whenever the arrays were rebuilt. Nothing on today's
 * paths rebuilds an identical selection — a change is published only when the selection actually
 * changes — so this is the type's contract being correct rather than a bug being fixed; the point is
 * that a caller comparing two of these, or a `GalleryState` holding one, gets the answer it expects.
 * Equality runs through the content-keyed set below, which is also what makes [contains] a lookup
 * rather than a scan of every selected id per tile.
 */
class GallerySelection(
    val active: Boolean = false,
    val ids: List<ByteArray> = emptyList(),
) {
    private val keys: Set<List<Byte>> = ids.mapTo(LinkedHashSet()) { it.toList() }

    val count: Int get() = ids.size

    /**
     * Whether [id] is selected. Compares by **content**: `ByteArray` equality is identity, so a tile
     * asking about a freshly decoded id would never match an id selected a moment earlier.
     */
    fun contains(id: ByteArray): Boolean = id.toList() in keys

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GallerySelection) return false
        return active == other.active && keys == other.keys
    }

    override fun hashCode(): Int = 31 * active.hashCode() + keys.hashCode()

    /** A count and a flag; never an id's bytes (hard rule #4). */
    override fun toString(): String = "GallerySelection(active=$active, count=$count)"
}

/**
 * One tile. The plaintext index columns ([kind], [size], [createdAt]) always render; the name and
 * MIME come from [meta], which may be [GalleryItemMeta.Unreadable].
 */
class GalleryItem(
    val id: ByteArray,
    val kind: VaultItemKind,
    val size: Long,
    val createdAt: Long,
    val meta: GalleryItemMeta,
    /**
     * The album this item is filed in, or `null` when it is in none — **and `null` on every listing
     * but the whole vault**, where the question does not arise: inside an album every item is in that
     * album, and nothing in Unfiled has one.
     *
     * An [AlbumTileName], so an album whose name will not open carries the same honest form it does
     * on the album grid rather than a blank or a guess.
     *
     * It costs no per-item decrypt. The item's row carries its album *id* as a plaintext column and
     * the name is mapped in from the album listing, which opens one small blob per album and nothing
     * per item (docs/ARCHITECTURE.md §5.1).
     */
    val album: AlbumTileName? = null,
) {
    // Content equality, because [id] is a ByteArray: the generated data-class equals would compare
    // it by identity and every re-emission of an unchanged listing would look like a change.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GalleryItem) return false
        return id.contentEquals(other.id) &&
            kind == other.kind &&
            size == other.size &&
            createdAt == other.createdAt &&
            meta == other.meta &&
            album == other.album
    }

    override fun hashCode(): Int {
        var result = id.contentHashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + meta.hashCode()
        result = 31 * result + album.hashCode()
        return result
    }

    /**
     * Sizes and id lengths only — never the decrypted name or MIME (hard rule #4).
     *
     * [album] reports **whether** the item is filed, never where: an album's name is decrypted
     * content exactly as a file's is.
     */
    override fun toString(): String =
        "GalleryItem(id=${id.size}B, kind=$kind, size=$size, createdAt=$createdAt, " +
            "readable=${meta is GalleryItemMeta.Readable}, filed=${album != null})"
}

/**
 * A tile's name and type, or the honest absence of them.
 *
 * Sealed on purpose: rendering has to branch, so a damaged item cannot be drawn as an ordinary one
 * by forgetting a null check. An item whose metadata will not open is **shown, not hidden** — the
 * catalog row exists, so silently dropping it would conceal tampering (docs/THREAT_MODEL.md).
 */
sealed interface GalleryItemMeta {
    /** The metadata opened and verified. */
    data class Readable(val name: String, val mime: String) : GalleryItemMeta {
        // Redacted like [GalleryItem]'s: these two fields are decrypted vault content, so the
        // generated data-class toString would print a file name into any log line that renders
        // this value (hard rule #4).
        override fun toString(): String = "Readable(name=${name.length} chars, mime=${mime.length} chars)"
    }

    /**
     * The metadata failed to open — corruption or tampering. The item still lists and can still be
     * shredded; only its name and type are unrecoverable. UI copy must not overstate which of the
     * two it was (hard rule #6).
     */
    data object Unreadable : GalleryItemMeta
}

/** A recoverable, non-fatal gallery failure, surfaced as honest copy (hard rule #6). */
enum class GalleryError {
    /** The import failed — an unreadable source, a full disk, or a catalog write that did not land. */
    ImportFailed,

    /** The listing itself failed; the vault is open but its catalog could not be read. */
    ListingFailed,

    /**
     * The move was refused outright — a destination album that is no longer there, or a catalog write
     * that did not land. Nothing moved; the items are where they were.
     */
    FilingFailed,
}
