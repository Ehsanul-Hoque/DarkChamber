package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumState
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
            when (search) {
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
                text = "${GalleryFormat.orderLabel(order)} $CARET",
                style = DarkChamberTheme.typography.label,
                color = colors.textPrimary,
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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

/** The disclosure caret. A glyph rather than an icon dependency, and hidden from the reader above. */
private const val CARET = "▾"

/**
 * Wide enough for the mark and no wider. Fixed rather than measured, because the point of the slot is
 * that all four rows agree on it — a width that followed its contents would defeat it.
 */
private val ORDER_MARK_SLOT = 20.dp
