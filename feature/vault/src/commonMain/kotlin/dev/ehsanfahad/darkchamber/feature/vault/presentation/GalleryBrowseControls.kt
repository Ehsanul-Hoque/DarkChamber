package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumState
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
import dev.ehsanfahad.darkchamber.feature.vault.api.GallerySearch
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState

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
            // Every order, from the enum rather than a list written out here: a fifth order would
            // otherwise be choosable nowhere and silently missing from this menu.
            for (candidate in GalleryOrder.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = GalleryFormat.orderLabel(candidate),
                            style = DarkChamberTheme.typography.body,
                            // The one in force is marked by colour rather than by a tick, which
                            // would need a glyph; it matches the collapsed label, so the menu says
                            // the same thing the row does.
                            color = if (candidate == order) colors.accent else colors.textPrimary,
                        )
                    },
                    onClick = {
                        open = false
                        onChooseOrder(candidate)
                    },
                )
            }
        }
    }
}

/** The disclosure caret. A glyph rather than an icon dependency, and hidden from the reader above. */
private const val CARET = "▾"
