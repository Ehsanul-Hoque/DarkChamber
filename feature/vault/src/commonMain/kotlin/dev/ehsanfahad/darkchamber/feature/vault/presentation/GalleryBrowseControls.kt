package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.core.designsystem.DisclosureCaret
import dev.ehsanfahad.darkchamber.core.designsystem.ExcludeFromPlatformStructures
import dev.ehsanfahad.darkchamber.core.designsystem.MoreActions
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumState
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
import dev.ehsanfahad.darkchamber.feature.vault.api.GallerySearch
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.OriginalDelete

/**
 * Whether the browse controls belong on screen at all.
 *
 * A predicate rather than a chain of conditions inside the composable, because there are no UI tests
 * in this project — a branch written into the layout is a branch nothing can check. Three of these
 * hide the row, and each is a case where sorting and searching could do nothing:
 *
 * - **a missing album:** that screen is a notice and one way out, not a listing;
 * - **unreadable:** the catalog failed. The items on screen are the last listing that *did* arrive
 *   and nothing will replace them, so the controls would be live over something stale — and a row
 *   offering to sort what could not be read invites fixing a failed read by choosing another order.
 * - **an empty listing:** nothing to order and nothing to find, so the row would be decoration.
 *
 * The last of those has an exception that is the whole reason this is written down: **a search that
 * matched nothing also has an empty listing**, and hiding the row then would take away the only way
 * to clear the query — the grid would be stuck empty with no visible cause.
 *
 * **An opening vault needs no clause of its own**, which is worth saying because the obvious version
 * of this has one. A listing that has not arrived is an empty listing, and `loading` is only ever
 * written `false` — nothing turns it back on — so a state cannot hold items while still loading. A
 * `!loading` term here would be a condition no state can satisfy, and removing it changes nothing a
 * test can see.
 */
internal fun GalleryState.showsBrowseControls(): Boolean =
    album !is AlbumState.Gone &&
        !listingUnreadable &&
        (items.isNotEmpty() || search is GallerySearch.Open)

/**
 * What a screen offers to do to **the album itself** — as opposed to its contents.
 *
 * These used to be two controls in a row of their own, which also repeated the album's name under the
 * name already in the bar. The row is gone and they are an overflow menu in the controls row instead;
 * this is where what the menu lists is decided, because a menu is easier to get wrong than the row
 * was. A row with no callback for a control simply left it out, while a menu lists whatever it is
 * handed — and an entry that does nothing is the kind of thing no test in this module can see.
 */
internal data class AlbumActions(val rename: Boolean, val delete: Boolean) {
    val any: Boolean get() = rename || delete
}

/**
 * Which of those this state offers.
 *
 * **Renaming needs a name that opened.** The rule comes across unchanged from the row it replaces:
 * with nothing to rename *from*, the dialog would prefill with a placeholder nobody has seen, so the
 * action is absent rather than present and dead. Deleting stays, because an album whose name will not
 * open is the one most likely to want removing — and deleting an album destroys none of its items.
 *
 * **A missing album offers neither.** That screen is a notice and one way out; acting on something
 * already gone is exactly what the browse controls avoid by not being drawn there either.
 *
 * The other two listings have no album to act on: the whole vault cannot be renamed, and Unfiled is a
 * view rather than a thing.
 */
internal fun GalleryState.albumActions(): AlbumActions =
    when (val album = album) {
        is AlbumState.Present ->
            AlbumActions(rename = album.name is AlbumTileName.Readable, delete = true)
        AlbumState.Gone, null -> AlbumActions(rename = false, delete = false)
    }

/**
 * Whether the row above the grid is drawn at all.
 *
 * Wider than [showsBrowseControls] by exactly one case, and that case is the reason this exists: an
 * **empty album**, or one whose listing would not read, has nothing to order and nothing to find — so
 * the browse controls are hidden — but it still has an album to rename or delete, and those are most
 * wanted precisely then. The row stays, carrying the overflow alone.
 *
 * An empty whole-vault listing still has no row: nothing to browse and no album to act on.
 */
internal fun GalleryState.showsControlsRow(): Boolean =
    showsBrowseControls() || albumActions().any

/**
 * The way back to seeing a batch that has just landed, when a control would keep it out of sight.
 *
 * Two different problems, so two different offers — and the order between them is the point of the
 * type: a **hidden** item cannot be scrolled to, so when both apply the search is cleared first.
 */
internal enum class ImportOffer {
    /** The results are filtered, so what was added may not be among them at all. */
    ClearSearch,

    /** Nothing is hidden; the new items are simply further down the grid. */
    ShowNewestFirst,
}

/**
 * Which offer a finished batch should carry, or `null` when the import is already where the user is
 * looking.
 *
 * Newest-first used to make this free: an import appeared at the top of the grid, where the user had
 * just been. Ordering and searching undo that premise — under a name order a batch lands mid-list,
 * and under a query it may not be listed at all while the card still reports it as imported.
 *
 * Three conditions silence the offer, each because it would otherwise claim something untrue:
 *
 * - **no finished batch:** nothing has just arrived, so there is nothing to point at;
 * - **nothing imported:** the batch added no items, so clearing a filter would reveal no change;
 * - **an open but empty field:** a blank query filters nothing, so the results are *not* filtered —
 *   which is the single claim this copy makes.
 *
 * Deliberately **not** an auto-clear: undoing something the user typed would change the screen under
 * them for a reason they never see. And deliberately **not** a scroll — that only addresses the
 * ordering case, and the state carries counts rather than ids, so nothing here knows where to scroll
 * to or how many of the new items a filter is hiding.
 */
internal fun GalleryState.importOffer(): ImportOffer? {
    val finished = batch as? ImportState.Finished ?: return null
    if (finished.imported == 0) return null
    return when {
        search.isFiltering() -> ImportOffer.ClearSearch
        order != GalleryOrder.Newest -> ImportOffer.ShowNewestFirst
        else -> null
    }
}

/**
 * What taking an offer does.
 *
 * A dispatch with no rule in it — the rule is *which* offer to make, which is [importOffer]'s — and
 * it still belongs out here, because a 1:1 mapping is precisely where a swapped branch survives:
 * "Clear search" that reordered the grid instead would be a control contradicting its own label, and
 * with no UI tests in this project a branch written into the layout is a branch nothing checks.
 *
 * Takes the screen's own callbacks rather than reaching for the component, so this stays the
 * mapping and nothing else.
 */
internal fun ImportOffer.take(
    onCloseSearch: () -> Unit,
    onChooseOrder: (GalleryOrder) -> Unit,
) = when (this) {
    ImportOffer.ClearSearch -> onCloseSearch()
    ImportOffer.ShowNewestFirst -> onChooseOrder(GalleryOrder.Newest)
}

/**
 * Whether the summary still has an offer to delete the imported originals.
 *
 * Two conditions, and both are about honesty rather than tidiness. **Nothing deletable is the
 * ordinary case** — every photo-library item, every iOS item, most cloud documents — so the control
 * is *absent* rather than present and disabled, which would be a control owing an explanation it
 * cannot give. And once the disclosure has been opened it owns the decision: an offer still standing
 * beside it would be a second way into something that has to be answered exactly once.
 */
internal fun ImportState.Finished.offersOriginalDelete(): Boolean =
    deletableOriginals > 0 && originalDelete == OriginalDelete.NotAsked

/**
 * What to say under the field about the items a search could not include, or `null` to say nothing.
 *
 * A function rather than a cast inside the layout, for the same reason [showsBrowseControls] is one:
 * this is the rule that a **closed** search says nothing — not because its count is zero, but because
 * it has no count at all — and a rule written into a composable is a rule nothing can check.
 *
 * The count itself is the listing's, published with it (see `GallerySearch.Open`), so this never has
 * to work out how many there are; it decides only whether there is anything to say.
 */
internal fun GallerySearch.hiddenNote(): String? =
    when (this) {
        GallerySearch.Closed -> null
        is GallerySearch.Open -> GalleryFormat.hiddenUnreadable(hiddenUnreadable)
    }

/**
 * Keeps the grid where the user left it when the browse controls change.
 *
 * **Re-ordering a listing moves the viewport on its own.** Measured on a device: standing at the top
 * of a Z–A listing and choosing A–Z left the scroll at index 0 at the moment the order changed and at
 * index 54 of 60 by the next measure — the far end, where the tile that had been at the top now lives.
 * Two things follow, and both are why this is a holder rather than a line in the layout.
 *
 * **The position has to be taken at the tap.** By the time the new order reaches the screen the grid
 * has already moved, so anything read then is the wrong number. [rememberPlaceBeforeReordering] is
 * called from the control's own callback, which is the last moment the old position is still true.
 *
 * **It has to be put back a frame later**, because the move happens in that re-measure and a scroll
 * issued before it would simply be overwritten.
 *
 * A changed **query** is the opposite case and needs no capture: the result set is new, so the grid
 * goes to its first match — see [scrollsToTop] for what counts as a change.
 *
 * Deliberately not an item key. Putting the order into the tiles' keys was tried first, on the theory
 * that a key the re-order could not match would leave the grid nothing to chase; the device showed the
 * viewport still moving, so the move is not a key remap, and the only thing that changed was every
 * tile being composed again on each order change.
 */
@Composable
internal fun rememberBrowsingScroll(
    gridState: LazyGridState,
    items: List<GalleryItem>,
    search: GallerySearch,
): BrowsingScroll {
    val scroll = remember(gridState) { BrowsingScroll(gridState) }

    scroll.onSearch(search)

    // **Before the frame is laid out, not after it.** A side effect runs once the composition that
    // carries the new listing has been applied and before that listing is measured, which is the only
    // moment a position can be asked for without a wrong one being drawn first. Correcting it
    // afterwards does work — and shows: on a recording the grid sat at the far end of the new listing
    // for six frames, about 50 ms, before snapping back.
    SideEffect { scroll.onListingComposed(items) }

    return scroll
}

/** The state behind [rememberBrowsingScroll]; see there for why each half exists. */
internal class BrowsingScroll(private val gridState: LazyGridState) {
    private var wanted: Place? = null
    private var lastSearch: GallerySearch? = null
    private var lastListing: List<GalleryItem>? = null

    /**
     * Whether a re-order is in flight — observable, because the grid reads it while composing.
     *
     * It gates the tile animation as well as the scroll: animating a re-order draws two tiles in one
     * slot, one fading out while the other fades in. True from the tap until the new listing has been
     * put back where the old one was, which is the whole window in which both listings exist.
     */
    var isReordering by mutableStateOf(false)
        private set

    /**
     * Called as an order is chosen, while the grid is still showing the old listing.
     *
     * **Choosing the order already in force arms nothing**, and the guard lives here rather than at
     * the call site because forgetting it is silent and lasting. The controls are a state flow, which
     * conflates an equal value — so re-choosing the current order produces no listing at all, and an
     * armed capture would simply wait. The next listing to arrive for any reason would then collect
     * it: filing one item sent the grid from the top back to the end of the listing, which is where it
     * had been when the order was re-chosen. Measured on a device before this guard existed.
     */
    fun rememberPlaceBeforeReordering(from: GalleryOrder, to: GalleryOrder) {
        if (from == to) return
        // **A grid with nothing in it has no position to hold** — and, worse, no way to tell the new
        // listing from the old one: an empty listing is the same instance every time (the ordering
        // returns the one shared empty list and an empty query hands it straight on), so the arrival
        // check below cannot see the second one. An armed capture would wait for a listing with items
        // in it — an import, a filing — and spend a position taken from an empty grid on it, having
        // suppressed that listing's arrival animation while it waited.
        if (lastListing.isNullOrEmpty()) return
        wanted = Place(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        isReordering = true
    }

    /**
     * Called while the search is read during composition, so a changed query has already asked for
     * the top by the time its results arrive.
     */
    fun onSearch(search: GallerySearch) {
        val previous = lastSearch
        lastSearch = search
        if (previous != null && scrollsToTop(previous, search)) wanted = Place(0, 0)
    }

    /**
     * Called for every composition; acts only on the one that carries a listing we have not seen.
     *
     * The guard is what keeps this to the case it is for: listings arrive for imports, filings and
     * renames too, and none of those should move the grid. Compared by identity — a new list instance
     * is exactly what a new emission is, and comparing contents would walk every item on every
     * composition to learn the same thing.
     */
    fun onListingComposed(items: List<GalleryItem>) {
        if (items === lastListing) return
        lastListing = items

        val place = wanted ?: return
        wanted = null
        // Asked for rather than scrolled to: this is applied *during* the measure that first lays the
        // new listing out, so the position the grid would otherwise have kept — the one holding the
        // old first item's key — is never used and never drawn.
        gridState.requestScrollToItem(place.index, place.offset)
        isReordering = false
    }

    private data class Place(val index: Int, val offset: Int)
}

/**
 * Whether a change to the search should put the grid back at the top.
 *
 * The grid **holds** its position when the order changes. A changed query is the other case: the
 * result set is new, its first match is the one worth seeing, and a held offset can leave the viewport
 * past the end of a short result — which looks exactly like matches having been dropped.
 *
 * What counts as a change is the query that actually **filters**, not the text in the field. Opening
 * the field filters nothing, whitespace filters nothing, and a trailing space is a typing artefact the
 * matcher trims — so none of those move the grid, while gaining or losing a real query does. Comparing
 * the effective needle rather than the raw string is what keeps this agreeing with the listing: if the
 * items did not change, neither does where the user is looking.
 */
internal fun scrollsToTop(previous: GallerySearch, next: GallerySearch): Boolean =
    previous.needle() != next.needle()

/** The text the listing actually filters by, which is nothing at all unless something was typed. */
private fun GallerySearch.needle(): String =
    if (isFiltering()) (this as GallerySearch.Open).query.trim() else ""

/**
 * One row of the order menu: an order to choose, and whether it is the one already in force.
 *
 * [inForce] is carried rather than recomputed at the point of drawing, so that "exactly one option is
 * marked" is a property of a value a test can hold rather than of a comparison buried in a layout.
 */
internal data class OrderOption(val order: GalleryOrder, val inForce: Boolean)

/**
 * Every order the menu offers, in the enum's own order, with the one in force marked.
 *
 * **From the enum, never a list written out here** — a fifth order added later must appear in this
 * menu or be choosable nowhere at all, and until this was a function nothing could check that. The
 * layout used to iterate the entries itself and carried a comment hoping for the same thing.
 *
 * The mark exists because the menu had no state to report. It coloured the order in force and said
 * nothing else, so assistive technology had nothing to announce and a view dump showed four identical
 * rows — a state carried only in a colour is also invisible to a reader who cannot separate the two
 * (docs/DESIGN.md §9 and §13).
 */
internal fun orderOptions(current: GalleryOrder): List<OrderOption> =
    GalleryOrder.entries.map { order -> OrderOption(order, inForce = order == current) }

/**
 * Whether this search is actually narrowing the listing — which is **not** the same as being open.
 *
 * Opening the field changes the controls row and nothing else; a blank query matches every name, so
 * until something is typed the grid on screen is the unfiltered listing. Two places need that
 * distinction and had written it out separately, which is how they came to disagree: the import offer
 * must not say results *may be hidden* when nothing filters them, and an empty grid must not blame a
 * query nobody typed.
 *
 * Blank rather than empty, because a field holding only spaces narrows nothing either — and the
 * search itself trims before matching, so anything else here would claim a filter the listing is not
 * applying.
 */
internal fun GallerySearch.isFiltering(): Boolean =
    this is GallerySearch.Open && query.isNotBlank()

/**
 * What an empty grid should say, out of the five answers the formatter holds.
 *
 * Which of the five wins is the formatter's rule and has its own tests; this is the other half —
 * **which state each of its questions is answered from** — and it is a function because that half
 * was wrong while every test passed. Asking whether the field was *open* rather than whether it was
 * *filtering* blamed an empty listing on a query the user never typed, reachable by emptying an
 * album with the field open: select everything, remove it, and the grid says no names matched.
 */
internal fun GalleryState.emptyListingMessage(): String =
    GalleryFormat.emptyListing(
        scope = scope,
        unreadable = listingUnreadable,
        searching = search.isFiltering(),
    )

/**
 * The gallery's own controls row: which order the grid is in, and the name being searched for.
 *
 * Under the app bar and owned by the feature, because sorting and searching are the listing's
 * concern and the bar belongs to the app module. **Not** in the bottom bar, which is for actions and
 * already wraps at five controls — the known failure this placement avoids.
 *
 * At most two controls in either state, which is the bounded version of what has broken twice at a
 * large font scale: collapsed, the current order plus the way into the field; expanded, the field
 * plus the way out of it. Collapsed it also names the order the grid is in, which otherwise cannot be
 * discovered without opening the menu.
 *
 * Under the controls, while there is something to say, the line about what a search had to leave out
 * ([hiddenNote]). It belongs here rather than beside the grid because it is a fact about the query,
 * and it reads with the field that produced it — on an empty result the grid's own message says
 * nothing matched, and this says how much was never looked at.
 */
@Composable
internal fun GalleryBrowseControls(
    order: GalleryOrder,
    search: GallerySearch,
    onChooseOrder: (GalleryOrder) -> Unit,
    onOpenSearch: () -> Unit,
    onEditSearch: (String) -> Unit,
    onCloseSearch: () -> Unit,
    // Whether the order and search controls themselves belong on the row — see [showsBrowseControls].
    // False while the row is drawn for the album's actions alone, which is how deleting an empty
    // album stays reachable now that it has no row of its own.
    showsControls: Boolean = true,
    albumActions: AlbumActions = AlbumActions(rename = false, delete = false),
    onRenameAlbum: () -> Unit = {},
    onDeleteAlbum: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = DarkChamberTheme.spacing
    val colors = DarkChamberTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.spaceMd, vertical = spacing.spaceXxs),
        ) {
            if (!showsControls) {
                // The row with nothing to browse: it holds the album's own actions and no more, so
                // the overflow sits where it always does rather than sliding to the left edge.
                Spacer(modifier = Modifier.weight(1f))
            }
            if (showsControls) when (search) {
                GallerySearch.Closed -> {
                    OrderControl(order = order, onChooseOrder = onChooseOrder)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = onOpenSearch,
                        modifier = Modifier.heightIn(min = spacing.spaceXxl),
                    ) {
                        Text(
                            text = GalleryFormat.searchNames(),
                            style = DarkChamberTheme.typography.label,
                            color = colors.accent,
                        )
                    }
                }

                is GallerySearch.Open -> {
                    // Focused as it appears. The field is here because the user asked for it, and a
                    // field that has to be tapped a second time to type into makes the first tap
                    // look like it did nothing.
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }

                    OutlinedTextField(
                        value = search.query,
                        onValueChange = onEditSearch,
                        label = { Text(GalleryFormat.searchNames()) },
                        singleLine = true,
                        // No automatic capitalisation: this is matched against a file name, and the
                        // match is case-folded anyway, so a capital letter the user did not type is
                        // noise in a field they are reading back.
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Done,
                        ),
                        // No keyboard action of its own: results narrow as each character arrives,
                        // so there is nothing for a submit key to do. Done gets the platform's own
                        // behaviour — put the keyboard away — rather than a handler doing nothing.
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                    TextButton(
                        onClick = onCloseSearch,
                        modifier = Modifier.heightIn(min = spacing.spaceXxl),
                    ) {
                        Text(
                            text = GalleryFormat.clearSearch(),
                            style = DarkChamberTheme.typography.label,
                            color = colors.accent,
                        )
                    }
                }
            }

            if (albumActions.any) {
                AlbumOverflow(
                    actions = albumActions,
                    onRename = onRenameAlbum,
                    onDelete = onDeleteAlbum,
                )
            }
        }

        search.hiddenNote()?.let { note ->
            Text(
                text = note,
                style = DarkChamberTheme.typography.caption,
                // The danger role, like every other line in this feature that says something would
                // not verify — the tile's name, an album's name, a failed import. A search is the
                // one place those items are *absent*, so a neutral footnote would be the quietest
                // possible way to report a signal the vault is built to surface.
                color = colors.danger,
                modifier = Modifier.padding(
                    start = spacing.spaceMd,
                    end = spacing.spaceMd,
                    bottom = spacing.spaceXs,
                ),
            )
        }
    }
}

/**
 * The order control: the current order, and the four to choose from behind it.
 *
 * **Whether the menu is open is not in the screen's state.** It is transient, guards no decision and
 * needs to survive nothing — unlike the modal dialogs, each of which is in state because something
 * is at stake behind it. A dropdown that reopened itself after a rebuild would be worse than one
 * that did not.
 */
@Composable
private fun OrderControl(order: GalleryOrder, onChooseOrder: (GalleryOrder) -> Unit) {
    val spacing = DarkChamberTheme.spacing
    val colors = DarkChamberTheme.colors
    var open by remember { mutableStateOf(false) }

    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier
                .heightIn(min = spacing.spaceXxl)
                // The caret is a drawing, and the label alone is a value rather than a control, so
                // the spoken name is supplied instead of inferred from either.
                .semantics { contentDescription = GalleryFormat.orderControlDescription(order) },
        ) {
            Text(
                text = GalleryFormat.orderLabel(order),
                style = DarkChamberTheme.typography.label,
                color = colors.textPrimary,
            )
            DisclosureCaret(
                // Decoration: the control's own description already says which order is in force and
                // that this opens a choice, so a second thing to announce would only repeat it.
                contentDescription = null,
                tint = colors.textPrimary,
                modifier = Modifier
                    .padding(start = spacing.spaceXxs)
                    // **Not its natural 24 dp.** The label beside it is 12 sp, and a 24 dp chevron
                    // next to 12 sp type is the loudest thing in the row — the opposite failure to
                    // the one being fixed. 16 dp from the scale is about half again the label's
                    // height, which is the proportion a disclosure caret has in Material's own
                    // controls. Being a filled outline, it keeps its shape at that size rather than
                    // thinning the way a stroke-drawn icon would.
                    .size(spacing.spaceMd),
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // A menu is its own window, like a dialog, so it excludes itself. Nothing in *this* one is
            // vault content — four fixed order names — but the rule is per window rather than per
            // wording: the menu that lists albums by name is the one that would otherwise be written
            // without this, and by then nothing would be asking the question.
            ExcludeFromPlatformStructures()
            // What to offer and which one to mark is decided in `orderOptions`, so that a fifth order
            // missing from this menu is a failing test rather than something to notice by eye.
            for (option in orderOptions(order)) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = GalleryFormat.orderLabel(option.order),
                            style = DarkChamberTheme.typography.body,
                            // Colour as well as the mark, not instead of it: it matches the collapsed
                            // label, so the menu reads the same way the row does.
                            color = if (option.inForce) colors.accent else colors.textPrimary,
                        )
                    },
                    // The state itself, for anything that reads the screen rather than looks at it.
                    // The platform owns the wording, which is why no copy here says "in use" — a
                    // hand-written suffix would be announced alongside it and localized by nobody.
                    modifier = Modifier.semantics { selected = option.inForce },
                    leadingIcon = { OrderMark(inForce = option.inForce) },
                    onClick = {
                        open = false
                        onChooseOrder(option.order)
                    },
                )
            }
        }
    }
}

/**
 * The album's own actions, behind one control.
 *
 * They were a row of two text buttons under a second copy of the album's name. The name was already
 * in the bar above — both copies truncated anyway — and renaming or deleting the album is not what
 * the screen is for, so a full row at all times was the wrong price: on a 3.5× screen it cost the
 * grid 224 px, which is a row of thumbnails.
 *
 * **What it offers is [AlbumActions]'s decision, not this layout's.** A menu lists what it is handed,
 * so an entry for an action that cannot run would look exactly like one that can — the row it
 * replaces could only ever omit a control it had no callback for.
 *
 * Whether the menu is open is not in the screen's state, for the same reason the order menu's is not:
 * it is transient, guards nothing, and a menu that reopened itself after a rebuild would be worse
 * than one that did not.
 */
@Composable
private fun AlbumOverflow(actions: AlbumActions, onRename: () -> Unit, onDelete: () -> Unit) {
    val spacing = DarkChamberTheme.spacing
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { open = true },
            // The full touch target, explicitly. The controls beside it ask for the same, and the
            // row this replaces was measured at 40 dp — short of the minimum — which is the defect
            // this change had to carry forward rather than inherit.
            modifier = Modifier.size(spacing.spaceXxl),
        ) {
            MoreActions(
                // Named for its subject rather than its shape: this is the whole control, so these
                // are the only words a screen reader has for it.
                contentDescription = GalleryFormat.albumActions(),
                modifier = Modifier.size(spacing.spaceLg),
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Its own window, so its own exclusion — see the order menu above for why this does not
            // wait for a menu that shows content.
            ExcludeFromPlatformStructures()
            if (actions.rename) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = GalleryFormat.renameAlbum(),
                            style = DarkChamberTheme.typography.body,
                            color = DarkChamberTheme.colors.textPrimary,
                        )
                    },
                    modifier = Modifier.heightIn(min = spacing.spaceXxl),
                    onClick = {
                        open = false
                        onRename()
                    },
                )
            }
            if (actions.delete) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = GalleryFormat.deleteAlbum(),
                            style = DarkChamberTheme.typography.body,
                            // Not the danger role, as in the row before it: deleting an album
                            // destroys none of its items, and colouring it as though it did would
                            // frighten someone away from a harmless action (hard rule 6).
                            color = DarkChamberTheme.colors.textPrimary,
                        )
                    },
                    modifier = Modifier.heightIn(min = spacing.spaceXxl),
                    onClick = {
                        open = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * The mark beside the order in force, and an equally wide gap beside the others.
 *
 * **Always occupies its slot**, which is the whole reason it is a composable rather than a
 * conditional: a mark drawn only on one row would indent that row's label and leave the other three
 * sitting somewhere else, so the eye would read the shift rather than the mark.
 *
 * **Silent to a reader.** The state is on the row itself as a selected semantic, which the platform
 * announces in its own words; this glyph says the same thing to an eye, and announced as well it
 * would be either a duplicate or the word "tick". The same mark the grid draws on a selected tile.
 */
@Composable
private fun OrderMark(inForce: Boolean) {
    Box(modifier = Modifier.width(ORDER_MARK_SLOT).clearAndSetSemantics {}) {
        if (inForce) {
            Text(
                text = "✓",
                style = DarkChamberTheme.typography.label,
                color = DarkChamberTheme.colors.accent,
            )
        }
    }
}

/**
 * Wide enough for the mark and no wider. Fixed rather than measured, because the point of the slot is
 * that all four rows agree on it — a width that followed its contents would defeat it.
 */
private val ORDER_MARK_SLOT = 20.dp
