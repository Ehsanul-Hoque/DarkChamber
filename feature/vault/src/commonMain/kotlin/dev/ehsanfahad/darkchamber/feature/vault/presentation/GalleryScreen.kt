package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import dev.ehsanfahad.darkchamber.core.designsystem.AlbumMark
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.core.designsystem.ExcludeFromPlatformStructures
import dev.ehsanfahad.darkchamber.core.designsystem.prefersReducedMotion
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDelete
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumState
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.Filing
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryOrder
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.api.GallerySelection
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.OriginalDelete
import dev.ehsanfahad.darkchamber.feature.vault.api.label

/**
 * The vault gallery (docs/DESIGN.md). Reads theme **roles** only — no raw hex, no hardcoded colors —
 * and leaves the full-circle radius to the aperture, so the import affordance is a rounded extended
 * FAB rather than a circle.
 *
 * Tiles draw their preview in a reserved square media slot, and the type badge in the same slot when
 * there is no preview — the two are the same size, so a grid does not relayout as previews resolve.
 * Tapping a tile does nothing yet; viewers arrive with the seekable read path.
 */
@Composable
internal fun GalleryScreen(
    state: GalleryState,
    now: Long,
    imageLoader: ImageLoader,
    onChooseOrder: (GalleryOrder) -> Unit,
    onOpenSearch: () -> Unit,
    onEditSearch: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onAddPhotos: () -> Unit,
    onAddFiles: () -> Unit,
    onCancelImport: () -> Unit,
    onAcknowledgeImport: () -> Unit,
    onRequestOriginalDelete: () -> Unit,
    onConfirmOriginalDelete: () -> Unit,
    onKeepOriginals: () -> Unit,
    onDismissError: () -> Unit,
    onSelectItem: (ByteArray) -> Unit,
    onToggleItem: (ByteArray) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onStartFiling: () -> Unit,
    onChooseAlbum: (ByteArray) -> Unit,
    onCancelFiling: () -> Unit,
    onUnfileSelection: () -> Unit,
    onStartFilingCreate: () -> Unit,
    onEditFilingAlbumName: (String) -> Unit,
    onConfirmFilingCreate: () -> Unit,
    onCancelFilingCreate: () -> Unit,
    onAcknowledgeFiling: () -> Unit,
    onStartAlbumRename: () -> Unit,
    onEditAlbumName: (String) -> Unit,
    onConfirmAlbumRename: () -> Unit,
    onCancelAlbumRename: () -> Unit,
    onRequestAlbumDelete: () -> Unit,
    onConfirmAlbumDelete: () -> Unit,
    onCancelAlbumDelete: () -> Unit,
    onLeaveAlbum: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = DarkChamberTheme.spacing
    val density = LocalDensity.current

    // The bottom bar floats over the grid, so the grid has to know how tall it actually is. Measured
    // rather than guessed at: the four batch states are very different heights (two buttons versus a
    // progress card carrying a file name and the keep-open notice), and every one of them grows again
    // when the OS text size is turned up. A constant that is right for one of them traps the last row
    // under the others.
    var barHeight by remember { mutableStateOf(0.dp) }

    // The grid's scroll, owned here rather than inside the grid because choosing an order has to read
    // it at the moment of the tap — see [BrowsingScroll].
    val gridState = rememberLazyGridState()
    val scroll = rememberBrowsingScroll(gridState, state.items, state.search)

    // No insets here: the host applies them once for the whole unlocked subtree, so a screen that
    // added its own would inset twice.
    val album = state.album

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            state.error?.let { error ->
                ErrorBanner(error = error, onDismiss = onDismissError)
            }

            // One row above the grid on all three listings, carrying the browse controls and — on an
            // album — the actions on the album itself. The album used to have a second row of its
            // own above this one, repeating the name already in the bar; both copies were truncated
            // anyway, and it cost the grid a row of thumbnails at all times.
            //
            // Two decisions, both held where a test can read them: [showsControlsRow] decides
            // whether the row is drawn at all — it stays for an album's actions even when there is
            // nothing to browse, which is when deleting an album matters most — and [albumActions]
            // decides what the menu offers.
            if (state.showsControlsRow()) {
                GalleryBrowseControls(
                    order = state.order,
                    search = state.search,
                    // The position is taken here, at the tap, because by the time the new order
                    // arrives the grid has already moved itself.
                    onChooseOrder = { chosen ->
                        scroll.rememberPlaceBeforeReordering(from = state.order, to = chosen)
                        onChooseOrder(chosen)
                    },
                    onOpenSearch = onOpenSearch,
                    onEditSearch = onEditSearch,
                    onCloseSearch = onCloseSearch,
                    showsControls = state.showsBrowseControls(),
                    albumActions = state.albumActions(),
                    onRenameAlbum = onStartAlbumRename,
                    onDeleteAlbum = onRequestAlbumDelete,
                )
            }

            when {
                // Ahead of loading and of the grid: an album that is no longer there has nothing to
                // list, and a spinner or an "empty album" message would both be lying about why.
                album is AlbumState.Gone -> MissingAlbumNotice(onLeave = onLeaveAlbum)
                state.loading -> CenteredMessage("Opening the vault…", showSpinner = true)
                // The listing failing and the listing being empty look identical from here — both are
                // an empty list with loading over — so the screen is told which it is rather than
                // letting the copy assert an absence nobody established. Not the banner's error, which
                // the user can dismiss: the reason the grid is empty outlives the acknowledgement.
                state.items.isEmpty() -> EmptyListing(message = state.emptyListingMessage())
                else -> ItemGrid(
                    items = state.items,
                    gridState = gridState,
                    reordering = scroll.isReordering,
                    now = now,
                    imageLoader = imageLoader,
                    bottomInset = barHeight,
                    selection = state.selection,
                    onSelectItem = onSelectItem,
                    onToggleItem = onToggleItem,
                )
            }
        }

        // Bottom of the screen: the import affordances, or — while a batch runs — what it is
        // doing and how to stop it. One at a time: offering "Add photos" mid-import would invite a
        // second batch the coordinator would only refuse.
        Box(
            contentAlignment = Alignment.BottomEnd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth()
                .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
                .bottomBarScrim()
                .padding(spacing.spaceLg),
        ) {
            // A move in progress, or its report, takes the bottom area ahead of everything — including
            // the selection bar, which is where it was started from and which has nothing left to say
            // while it runs.
            val filing = state.filing
            if (filing is Filing.Moving || filing is Filing.Moved) {
                FilingCard(filing = filing, onDismiss = onAcknowledgeFiling)
            } else if (state.selection.active) {
                // Selecting takes the bottom area over entirely, ahead of any batch state. That is also
                // why nothing guards against starting an import mid-selection: the buttons that would
                // start one are not on screen while this is.
                SelectionBar(
                    count = state.selection.count,
                    // Offered only where every selected item is in the album being viewed. On the whole
                    // vault the selection is a mix of filed and unfiled items, so the button would
                    // silently do nothing to some of them; from Unfiled there is nothing to take out.
                    // No shredding here either — that is destructive, needs its own disclosure, and
                    // belongs to the work that owns it.
                    onUnfile = onUnfileSelection.takeIf { state.scope == GalleryScope.Album },
                    onMoveToAlbum = onStartFiling,
                    onSelectAll = onSelectAll,
                    onClear = onClearSelection,
                )
            } else when (val batch = state.batch) {
                is ImportState.Running -> ImportProgressCard(batch = batch, onCancel = onCancelImport)
                // Not "Choose what to add…" any more. This card is held back until a pick is taking
                // long enough to be worth explaining, and by then the likeliest reason it is showing
                // is the stretch *after* choosing, while the system materialises the selection — at
                // which point telling the user to choose is telling them to do something they have
                // already done. Vague on purpose: this layer cannot tell which of the two waits it
                // is in, and naming the wrong one would be worse than naming neither.
                ImportState.Picking -> ImportNoticeCard(text = "Getting things ready…")
                is ImportState.Finished -> ImportSummaryCard(
                    batch = batch,
                    offer = state.importOffer(),
                    onDismiss = onAcknowledgeImport,
                    onDeleteOriginals = onRequestOriginalDelete,
                    // Both halves live outside this layout: which offer to make, and what taking it
                    // does — see `importOffer` and `take`.
                    onTakeOffer = { offer ->
                        offer.take(onCloseSearch = onCloseSearch, onChooseOrder = onChooseOrder)
                    },
                )
                ImportState.Idle -> ImportActions(onAddPhotos = onAddPhotos, onAddFiles = onAddFiles)
                // Not the failed-file banner: nothing was chosen, so there is no file to blame
                // (hard rule 6). It sits above the actions rather than replacing them, because the
                // fix for a picker that would not open is usually to tap again.
                ImportState.PickerUnavailable -> PickerUnavailableCard(
                    onDismiss = onAcknowledgeImport,
                    onAddPhotos = onAddPhotos,
                    onAddFiles = onAddFiles,
                )
            }
        }

        // Above everything, because it is the one thing here that destroys something. Only ever
        // reached by taking the offer on the summary.
        val batch = state.batch
        val deleteState = (batch as? ImportState.Finished)?.originalDelete
        if (deleteState == OriginalDelete.Confirming || deleteState == OriginalDelete.Deleting) {
            DeleteOriginalsDisclosure(
                count = batch.deletableOriginals,
                // The dialog stays up while the deletes run, with both buttons spent. The coordinator
                // refuses either intent in that state anyway; disabling them is so the dialog stops
                // *offering* a choice that has already been made and cannot be taken back.
                answered = deleteState == OriginalDelete.Deleting,
                onDelete = onConfirmOriginalDelete,
                onKeep = onKeepOriginals,
            )
        }

        // The chooser, above the grid the selection was made in.
        (state.filing as? Filing.Choosing)?.let { choosing ->
            AlbumChooser(
                choosing = choosing,
                selectedCount = state.selection.count,
                onChoose = onChooseAlbum,
                onCancel = onCancelFiling,
                onStartCreate = onStartFilingCreate,
                onEditName = onEditFilingAlbumName,
                onConfirmCreate = onConfirmFilingCreate,
                onCancelCreate = onCancelFilingCreate,
            )
        }

        // The album's own two dialogs, above the grid they act on. Never both: each intent refuses
        // while the other is open.
        if (album is AlbumState.Present) {
            album.rename?.let { draft ->
                AlbumNameDialog(
                    title = GalleryFormat.renameAlbum(),
                    draft = draft,
                    confirmLabel = "Rename",
                    savingLabel = "Renaming…",
                    onNameChange = onEditAlbumName,
                    onConfirm = onConfirmAlbumRename,
                    onCancel = onCancelAlbumRename,
                )
            }

            album.delete?.let { delete ->
                DeleteAlbumConfirmation(
                    // Null while the listing has not arrived: the heading this dialog opens from
                    // appears as soon as the album is known, which is independent of the item
                    // listing, so an empty list here can mean "not read yet" rather than "empty".
                    itemCount = state.items.size.takeIf { !state.loading && !state.listingUnreadable },
                    delete = delete,
                    onDelete = onConfirmAlbumDelete,
                    onKeep = onCancelAlbumDelete,
                )
            }
        }
    }
}

/**
 * Where the selected items should go.
 *
 * A list of albums and nothing else — no covers, no counts: this is answering a question, and a grid of
 * decorated tiles would make it look like somewhere to browse.
 *
 * The explanation above the list is not filler. It is the only place the user is told that an item lives
 * in **one** album, which is what makes this a move: anything already filed leaves where it was. The
 * word "add" is avoided throughout for that reason (hard rule #6).
 *
 * Creating is offered here, and it is the whole answer to an empty vault: having just been asked to
 * choose, a user with no albums would otherwise face an empty list and a cancel button.
 */
@Composable
private fun AlbumChooser(
    choosing: Filing.Choosing,
    selectedCount: Int,
    onChoose: (ByteArray) -> Unit,
    onCancel: () -> Unit,
    onStartCreate: () -> Unit,
    onEditName: (String) -> Unit,
    onConfirmCreate: () -> Unit,
    onCancelCreate: () -> Unit,
) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    val creating = choosing.creating

    Dialog(
        onDismissRequest = onCancel,
        // Dismissable: nothing has happened yet, and the worst a stray tap costs is reopening it.
        properties = DialogProperties(
            dismissOnBackPress = creating?.saving != true,
            dismissOnClickOutside = creating?.saving != true,
        ),
    ) {
        // Its own window, so its own exclusion. Every dialog does this whether or not it holds a
        // field today — the album names listed here are vault content too, and a dialog that gains
        // a field later must not have to remember.
        ExcludeFromPlatformStructures()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = colors.surface, shape = DarkChamberTheme.shapes.large)
                .padding(spacing.spaceLg),
        ) {
            Text(
                text = GalleryFormat.moveToAlbum(),
                style = DarkChamberTheme.typography.title,
                color = colors.textPrimary,
            )
            Text(
                text = GalleryFormat.chooserExplanation(selectedCount),
                style = DarkChamberTheme.typography.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.spaceXs),
            )

            if (creating != null) {
                OutlinedTextField(
                    value = creating.name,
                    onValueChange = onEditName,
                    label = { Text(GalleryFormat.albumNameLabel()) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirmCreate() }),
                    isError = creating.problem != null,
                    enabled = !creating.saving,
                    modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceMd),
                )
                creating.problem?.let { problem ->
                    Text(
                        text = GalleryFormat.albumDraftProblem(problem),
                        style = DarkChamberTheme.typography.caption,
                        color = colors.danger,
                        modifier = Modifier.padding(top = spacing.spaceXs),
                    )
                }
            } else if (choosing.albums.isEmpty()) {
                Text(
                    text = GalleryFormat.chooserEmpty(),
                    style = DarkChamberTheme.typography.body,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = spacing.spaceMd),
                )
            } else {
                // Scrollable and height-capped: a vault with thirty albums must not push the buttons
                // off the bottom of the screen.
                LazyColumn(modifier = Modifier.heightIn(max = CHOOSER_LIST_MAX).padding(top = spacing.spaceSm)) {
                    items(choosing.albums, key = { it.id.toListKey() }) { choice ->
                        Text(
                            text = choice.name.label(),
                            style = DarkChamberTheme.typography.body,
                            color = if (choice.name is AlbumTileName.Unreadable) colors.danger else colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                // An album whose name will not open is still somewhere items can go: the
                                // name is damaged, the album is not, and excluding it would strand the
                                // only route back into it.
                                .clickable { onChoose(choice.id) }
                                .padding(vertical = spacing.spaceSm),
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceMd),
            ) {
                TextButton(
                    onClick = if (creating == null) onCancel else onCancelCreate,
                    enabled = creating?.saving != true,
                ) {
                    Text(
                        text = if (creating == null) "Cancel" else "Back",
                        style = DarkChamberTheme.typography.label,
                        color = colors.textPrimary,
                    )
                }
                TextButton(
                    onClick = if (creating == null) onStartCreate else onConfirmCreate,
                    enabled = creating?.saving != true,
                ) {
                    Text(
                        // The create's label says what the whole step does, because it does both: the
                        // album is made and the selection goes into it.
                        text = when {
                            creating == null -> GalleryFormat.newAlbum()
                            creating.saving -> "Moving…"
                            else -> "Create and move"
                        },
                        style = DarkChamberTheme.typography.label,
                        color = if (creating?.saving == true) colors.textSecondary else colors.accent,
                    )
                }
            }
        }
    }
}

/**
 * A move running, or what came of it.
 *
 * Shaped like the import's cards rather than as a dialog: it is the same kind of news in the same place,
 * and a modal over a finished action would ask for a tap to dismiss something nobody needs to read
 * twice.
 */
@Composable
private fun FilingCard(filing: Filing, onDismiss: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.surface, shape = DarkChamberTheme.shapes.large)
            .padding(spacing.spaceMd),
    ) {
        when (filing) {
            is Filing.Moving -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = colors.accent,
                    modifier = Modifier.size(DarkChamberTheme.spacing.spaceMd),
                )
                Text(
                    text = "Moving…",
                    style = DarkChamberTheme.typography.body,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = spacing.spaceSm),
                )
            }

            is Filing.Moved -> {
                Text(
                    text = GalleryFormat.movedCount(filing.moved, filing.requested, filing.destination),
                    style = DarkChamberTheme.typography.body,
                    color = colors.textPrimary,
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "OK",
                            style = DarkChamberTheme.typography.label,
                            color = colors.accent,
                        )
                    }
                }
            }

            // The chooser is a dialog, so this card is never asked to draw it.
            is Filing.Choosing -> Unit
        }
    }
}

/**
 * The album is not there any more, said where the user is already looking — then one tap back to the
 * grid.
 *
 * The notice comes **before** the exit rather than after it: a message shown on the screen being
 * popped is a message nobody reads, and popping silently would leave someone wondering where their
 * files went. The grid they are looking at has just emptied, which is exactly what losing them would
 * look like.
 */
@Composable
private fun MissingAlbumNotice(onLeave: () -> Unit) {
    val spacing = DarkChamberTheme.spacing

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(spacing.spaceLg),
    ) {
        Text(
            text = GalleryFormat.albumGone(),
            style = DarkChamberTheme.typography.body,
            color = DarkChamberTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onLeave, modifier = Modifier.padding(top = spacing.spaceSm)) {
            Text(
                text = GalleryFormat.backToAlbums(),
                style = DarkChamberTheme.typography.label,
                color = DarkChamberTheme.colors.accent,
            )
        }
    }
}

/**
 * Deleting an album, and the one piece of copy on this screen that has to correct an expectation
 * rather than confirm one.
 *
 * Dismissable, unlike the import disclosure: that one warns about something irreversible, while this
 * one exists to explain that **nothing is lost**. Making it inescapable would imply the opposite of
 * what it says.
 *
 * Neither button leads. Deleting is a perfectly ordinary thing to do to an album and keeping it is
 * equally fine, so they read as two options rather than a default and an escape — and the delete does
 * not take the danger role, because it destroys nothing.
 */
@Composable
private fun DeleteAlbumConfirmation(
    /** How many items are in the album, or `null` if that is not known yet — which is not zero. */
    itemCount: Int?,
    delete: AlbumDelete,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    val running = delete == AlbumDelete.Deleting

    Dialog(
        onDismissRequest = onKeep,
        // While the two writes run there is nothing to dismiss *to*: the delete cannot be called back,
        // and a dialog that vanished would be claiming it had been.
        properties = DialogProperties(dismissOnBackPress = !running, dismissOnClickOutside = !running),
    ) {
        ExcludeFromPlatformStructures()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = colors.surface, shape = DarkChamberTheme.shapes.large)
                .padding(spacing.spaceLg),
        ) {
            Text(
                text = GalleryFormat.deleteAlbumQuestion(),
                style = DarkChamberTheme.typography.title,
                color = colors.textPrimary,
            )
            Text(
                text = GalleryFormat.deleteAlbumExplanation(itemCount),
                style = DarkChamberTheme.typography.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.spaceSm),
            )

            if (delete == AlbumDelete.Failed) {
                Text(
                    text = GalleryFormat.deleteAlbumFailed(),
                    style = DarkChamberTheme.typography.caption,
                    color = colors.danger,
                    modifier = Modifier.padding(top = spacing.spaceSm),
                )
            }

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceMd),
            ) {
                TextButton(onClick = onKeep, enabled = !running) {
                    Text(
                        text = "Keep it",
                        style = DarkChamberTheme.typography.label,
                        color = if (running) colors.textSecondary else colors.textPrimary,
                    )
                }
                TextButton(onClick = onDelete, enabled = !running) {
                    Text(
                        text = if (running) "Deleting…" else GalleryFormat.deleteAlbum(),
                        style = DarkChamberTheme.typography.label,
                        color = if (running) colors.textSecondary else colors.accent,
                    )
                }
            }
        }
    }
}

/**
 * The disclosure, which is **not dismissable** (docs/ARCHITECTURE.md): no back press, no tap
 * outside, no dismissal of any kind — only the two buttons.
 *
 * That is not friction for its own sake. This dialog is the only place a user is told what deleting
 * an original does and does not achieve, so a dialog they can tap past is a deletion carried out on
 * a warning they never read (hard rule #6). The copy itself lives in [GalleryFormat], where it is
 * tested against what `docs/THREAT_MODEL.md` permits.
 *
 * Neither button leads: deleting is not the recommended choice, and keeping the original is a
 * perfectly good answer — so they read as two equal options rather than a default and an escape.
 */
@Composable
private fun DeleteOriginalsDisclosure(
    count: Int,
    answered: Boolean,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Dialog(
        // Nothing to do: with both dismissals disabled this cannot fire, and treating a dismissal
        // as "keep" would quietly accept a gesture that must not exist here.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        ExcludeFromPlatformStructures()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = colors.surface, shape = DarkChamberTheme.shapes.large)
                .padding(spacing.spaceLg),
        ) {
            Text(
                text = GalleryFormat.deleteOriginals(count) + "?",
                style = DarkChamberTheme.typography.title,
                color = colors.textPrimary,
            )
            Text(
                text = GalleryFormat.deleteOriginalsDisclosure(count),
                style = DarkChamberTheme.typography.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.spaceSm),
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceMd),
            ) {
                TextButton(onClick = onKeep, enabled = !answered) {
                    Text(
                        text = "Keep them",
                        style = DarkChamberTheme.typography.label,
                        color = if (answered) colors.textSecondary else colors.textPrimary,
                    )
                }
                TextButton(onClick = onDelete, enabled = !answered) {
                    // The destructive role, so the irreversible choice does not look like the safe
                    // one sitting next to it — and its own past tense once it has been taken, which
                    // is the only thing on screen saying why the buttons stopped responding.
                    Text(
                        text = if (answered) "Deleting…" else "Delete",
                        style = DarkChamberTheme.typography.label,
                        color = if (answered) colors.textSecondary else colors.danger,
                    )
                }
            }
        }
    }
}

/**
 * Fades the screen's own background up behind the bottom bar, so whatever is scrolling underneath
 * stops competing with the text on top of it.
 *
 * This exists for **"Add files"**, which is the one thing down there with no container of its own —
 * the extended FAB and all three import cards carry their own fill, and a plain label over someone's
 * photo thumbnail is unreadable. Giving that button a container instead would have been the obvious
 * fix and the wrong one: two filled buttons side by side read as two equal choices, and the whole
 * point of the pairing is that adding photos leads.
 *
 * Not `colors.scrim` — that role is the 60%/40% blackout behind a dialog, which would darken the
 * bottom of the gallery as though something modal were open. This is the background colour arriving
 * gradually, which is what is actually happening: content scrolls under the bar and out of sight.
 */
@Composable
private fun Modifier.bottomBarScrim(): Modifier {
    val background = DarkChamberTheme.colors.background
    return background(
        Brush.verticalGradient(
            // Weighted towards the top of the bar: a linear ramp still leaves the first third too
            // thin to read against a bright thumbnail.
            0f to background.copy(alpha = 0f),
            0.45f to background.copy(alpha = 0.85f),
            1f to background,
        ),
    )
}

/** "Add photos" leads, because the library is where most of what people vault already lives. */
@Composable
private fun ImportActions(onAddPhotos: () -> Unit, onAddFiles: () -> Unit) {
    val spacing = DarkChamberTheme.spacing

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onAddFiles) {
            Text(
                text = "Add files",
                style = DarkChamberTheme.typography.label,
                color = DarkChamberTheme.colors.accent,
            )
        }
        Spacer(Modifier.width(spacing.spaceXs))
        ExtendedFloatingActionButton(
            onClick = onAddPhotos,
            containerColor = DarkChamberTheme.colors.accent,
            contentColor = DarkChamberTheme.colors.onAccent,
            // Rounded, never a circle: the full-circle radius belongs to the aperture (DESIGN.md §7).
            shape = DarkChamberTheme.shapes.large,
        ) {
            Text(text = "Add photos", style = DarkChamberTheme.typography.label)
        }
    }
}

/**
 * The selection bar: how many are selected, the two ways to change that wholesale, and what can be
 * done with it.
 *
 * It replaces the import affordances rather than sitting beside them — one bottom bar at a time, as
 * the batch states already are. Filing is the only action here: shredding is destructive and needs a
 * disclosure of its own, so it arrives with the work that owns shredding rather than being offered
 * before it can honestly be explained.
 *
 * The count is the whole message when nothing can be acted on. It is also the one honest thing to
 * say while the selection is being intersected against a listing that can change underneath it.
 *
 * **The count sits on its own line, above the actions.** Inside an album there are four actions, and
 * sharing one row with the count overflowed the screen: on a 1440px phone at the default text size
 * "Done" was pushed off the right edge entirely and "Select all" wrapped onto two lines, so the only
 * way out of selecting mode was the system back button. A `Row` does not wrap or scroll — it just
 * places children past the edge — so the fix is to stop asking it to fit five things. Giving the
 * actions the full width leaves room for all four with margin to spare at larger text sizes.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onUnfile: (() -> Unit)?,
    onMoveToAlbum: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    val spacing = DarkChamberTheme.spacing

    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = GalleryFormat.selectedCount(count),
            style = DarkChamberTheme.typography.label,
            color = DarkChamberTheme.colors.textPrimary,
            modifier = Modifier.padding(end = spacing.spaceSm),
        )
        SelectionActions(
            count = count,
            onUnfile = onUnfile,
            onMoveToAlbum = onMoveToAlbum,
            onSelectAll = onSelectAll,
            onClear = onClear,
        )
    }
}

/**
 * The actions, wrapping onto as many lines as they need.
 *
 * **A `Row` is the wrong container here and giving it more width only moved the cliff.** With the count
 * on its own line all four fitted at the default text size, and at 1.5× they did not: "Select all" was
 * squeezed to a few pixels wide and "Done" left the screen again. The widths are text, so they scale
 * with the user's font setting and no arrangement of a single row survives every setting. Flowing them
 * means the layout answers the question at run time instead of a developer guessing on one device.
 */
@Composable
private fun SelectionActions(
    count: Int,
    onUnfile: (() -> Unit)?,
    onMoveToAlbum: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.End,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        onUnfile?.let { unfile ->
            TextButton(onClick = unfile, enabled = count > 0) {
                Text(
                    text = GalleryFormat.unfileSelection(),
                    style = DarkChamberTheme.typography.label,
                    // Not the danger role: nothing is destroyed, and colouring it as though something
                    // were is how a user comes to believe this button deletes their files.
                    color = if (count > 0) DarkChamberTheme.colors.accent else DarkChamberTheme.colors.textSecondary,
                )
            }
        }
        TextButton(onClick = onMoveToAlbum, enabled = count > 0) {
            Text(
                text = GalleryFormat.moveToAlbum(),
                style = DarkChamberTheme.typography.label,
                // Disabled rather than absent at zero, unlike the album actions elsewhere: the mode
                // itself persists with nothing in it (an item can leave the listing while selected), so
                // a button vanishing and returning as the count changes would be the thing that reads
                // as broken.
                color = if (count > 0) DarkChamberTheme.colors.accent else DarkChamberTheme.colors.textSecondary,
            )
        }
        TextButton(onClick = onSelectAll) {
            Text(
                text = "Select all",
                style = DarkChamberTheme.typography.label,
                color = DarkChamberTheme.colors.accent,
            )
        }
        TextButton(onClick = onClear) {
            Text(
                text = "Done",
                style = DarkChamberTheme.typography.label,
                color = DarkChamberTheme.colors.accent,
            )
        }
    }
}

/**
 * A running batch: which file of how many, how far into it, the notice, and a way out.
 *
 * The per-file indicator is **determinate only when the platform gave a size**; otherwise it is
 * indeterminate, because a bar filling against an invented total misreports progress (hard rule #6).
 * The file count is always shown, so there is something truthful to watch either way.
 */
@Composable
private fun ImportProgressCard(batch: ImportState.Running, onCancel: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.surfaceRaised, shape = DarkChamberTheme.shapes.large)
            .padding(spacing.spaceMd),
    ) {
        Text(
            text = "Importing ${batch.index + 1} of ${batch.total}",
            style = DarkChamberTheme.typography.body,
            color = colors.textPrimary,
        )
        Text(
            text = batch.name,
            style = DarkChamberTheme.typography.caption,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = spacing.spaceXs),
        )

        // Only the stages that cannot be measured say anything, and only once they have lasted — so
        // an ordinary import shows no line here at all and the card never changes height. A slow one
        // gains a line for as long as it is slow, which is the case where a bar that cannot move
        // needs explaining.
        GalleryFormat.stageLabel(batch.stage)?.let { label ->
            Text(
                text = label,
                style = DarkChamberTheme.typography.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.spaceXs),
            )
        }

        val fraction = batch.fileFraction
        if (fraction == null) {
            LinearProgressIndicator(
                color = colors.accent,
                trackColor = colors.surface,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceXs),
            )
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                color = colors.accent,
                trackColor = colors.surface,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceXs),
            )
        }

        Text(
            // Once stopping, the keep-open notice is the wrong warning: the batch is already ending,
            // and what the user needs to know is that it has heard them and that nothing already
            // added is at risk. Which wait it is in is said by the stage line above rather than
            // guessed at here.
            //
            // Both take the stage, because both gain a clause while a provider is still sending the
            // file and neither may claim it at any other time — see `GalleryFormat`.
            text = if (batch.stopping) {
                GalleryFormat.stoppingNotice(batch.stage)
            } else {
                GalleryFormat.keepOpenNotice(batch.stage)
            },
            style = DarkChamberTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = spacing.spaceXs),
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel, enabled = !batch.stopping) {
                // Says what cancelling actually costs: the files already in the vault stay there.
                Text(
                    text = if (batch.stopping) "Stopping…" else "Stop (keeps what's added)",
                    style = DarkChamberTheme.typography.label,
                    // Disabled is carried by the colour too, not by the label alone: a button that
                    // still looks live invites a second tap that does nothing.
                    color = if (batch.stopping) colors.textSecondary else colors.accent,
                )
            }
        }
    }
}

/**
 * Shown when the platform would not present a picker at all.
 *
 * Says what happened and no more. We do not know *why* — no activity to host the intent, no view
 * controller to present from, a provider that fell over — and guessing would be worse than the plain
 * fact (hard rule 6). It deliberately does **not** reuse the failed-file banner: no file was chosen,
 * so naming one would invent a victim.
 *
 * The add actions stay underneath it, because a picker that failed once is usually fixed by tapping
 * again, and a notice that took the buttons away would leave the screen with nothing to do.
 */
@Composable
private fun PickerUnavailableCard(onDismiss: () -> Unit, onAddPhotos: () -> Unit, onAddFiles: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.spaceSm)
                .background(color = colors.surfaceRaised, shape = DarkChamberTheme.shapes.large)
                .padding(start = spacing.spaceMd, end = spacing.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "That picker wouldn't open. Nothing was added.",
                style = DarkChamberTheme.typography.body,
                color = colors.textPrimary,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = spacing.spaceSm),
            )
            TextButton(onClick = onDismiss) {
                Text("OK", style = DarkChamberTheme.typography.label, color = colors.accent)
            }
        }
        ImportActions(onAddPhotos = onAddPhotos, onAddFiles = onAddFiles)
    }
}

/** The gap between tapping and the picker appearing, where the notice already applies. */
@Composable
private fun ImportNoticeCard(text: String) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.surfaceRaised, shape = DarkChamberTheme.shapes.large)
            .padding(spacing.spaceMd),
    ) {
        Text(text = text, style = DarkChamberTheme.typography.body, color = colors.textPrimary)
        Text(
            // No stage: this card is the gap between tapping and the picker appearing, so no file
            // has been reached and nothing is being transferred to warn about.
            text = GalleryFormat.keepOpenNotice(stage = null),
            style = DarkChamberTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = spacing.spaceXs),
        )
    }
}

/**
 * What a finished batch amounts to. Says how many could not be read rather than implying everything
 * worked, and never claims a reason it does not have (hard rule #6).
 *
 * [offer] is the way back to seeing what just arrived, when a control would keep it out of sight —
 * see [importOffer], which decides whether there is one.
 */
@Composable
private fun ImportSummaryCard(
    batch: ImportState.Finished,
    offer: ImportOffer?,
    onDismiss: () -> Unit,
    onDeleteOriginals: () -> Unit,
    onTakeOffer: (ImportOffer) -> Unit,
) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.sealedContainer, shape = DarkChamberTheme.shapes.large),
    ) {
        ImportSummaryLine(batch = batch, onDismiss = onDismiss)

        // A line of its own rather than a third control in the row above. That row already carries
        // the dismissal and, sometimes, the delete offer — and a bottom bar wrapping at five
        // controls is this screen's known failure. Two per line, like the browse row.
        offer?.let { shown ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.spaceMd, bottom = spacing.spaceXs),
            ) {
                Text(
                    text = GalleryFormat.importOfferExplanation(shown),
                    style = DarkChamberTheme.typography.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onTakeOffer(shown) }) {
                    Text(
                        text = GalleryFormat.importOfferAction(shown),
                        style = DarkChamberTheme.typography.label,
                        color = colors.accent,
                    )
                }
            }
        }

        // Last, and furthest from both the dismissal and the position the picker had — see
        // [OriginalDeleteRow]. Absent unless there is something to offer and nobody has answered yet
        // ([offersOriginalDelete]), so the common case is the message and Done alone.
        if (batch.offersOriginalDelete()) {
            OriginalDeleteRow(count = batch.deletableOriginals, onDeleteOriginals = onDeleteOriginals)
        }
    }
}

/**
 * The summary itself: what came of the batch, and the one safe thing to do about it.
 *
 * Only the dismissal lives here. The destructive action is a row of its own below — see
 * [OriginalDeleteRow] for why it is not beside this one.
 */
@Composable
private fun ImportSummaryLine(batch: ImportState.Finished, onDismiss: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = spacing.spaceMd),
    ) {
        // The vertical padding only shows once the message wraps: on one line the row is already
        // taller than the text, because the buttons carry a 48 dp touch target. A two-line message
        // outgrows that, and at 8 dp it sat against the card's edge — so the message keeps a
        // comfortable inset of its own rather than inheriting the button's.
        Column(modifier = Modifier.weight(1f).padding(vertical = spacing.spaceSm)) {
            Text(
                text = GalleryFormat.importedCount(batch.imported),
                style = DarkChamberTheme.typography.body,
                color = colors.textPrimary,
            )
            if (batch.failed.isNotEmpty()) {
                Text(
                    text = GalleryFormat.failedCount(batch.failed.size),
                    style = DarkChamberTheme.typography.caption,
                    color = colors.danger,
                )
            }
            // What came of a deletion, once one has happened — including the partial result, which
            // is the whole reason this is a count and not a tick.
            (batch.originalDelete as? OriginalDelete.Done)?.let { done ->
                Text(
                    text = GalleryFormat.deletedOriginals(done.deleted, done.requested),
                    style = DarkChamberTheme.typography.caption,
                    color = if (done.deleted < done.requested) colors.danger else colors.textSecondary,
                )
            }
        }

        // **The safe action, in the position the picker occupied.** After a batch the card replaces
        // the import affordances, so whatever sits here is what a finger returning from "Add photos"
        // finds — and that has to be the one tap that costs nothing.
        TextButton(onClick = onDismiss) {
            Text("Done", style = DarkChamberTheme.typography.label, color = colors.accent)
        }
    }
}

/**
 * Deleting the originals, on a row of its own at the bottom of the card.
 *
 * **It used to sit beside Done**, which put it within a few dp of where "Add photos" had been moments
 * earlier — so importing repeatedly trained a finger for a spot that then opened the delete
 * disclosure. Nothing is destroyed by that tap (the disclosure is non-dismissable and has to be
 * answered) but a destructive control inheriting a safe one's position is a trap that only needs one
 * distracted moment.
 *
 * Its own row, at the far end of the card from the dismissal, and **outlined rather than plain text**:
 * the earlier shape of this — muted text under the message — is exactly how it came to read as a
 * stray line of the summary, which is the bug that moved it up beside Done in the first place. A
 * bordered control is unmistakably a control, while staying quieter than the accent on Done.
 */
@Composable
private fun OriginalDeleteRow(count: Int, onDeleteOriginals: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.spaceMd, end = spacing.spaceMd, bottom = spacing.spaceSm),
    ) {
        OutlinedButton(
            onClick = onDeleteOriginals,
            shape = DarkChamberTheme.shapes.medium,
            border = BorderStroke(width = TILE_BORDER, color = colors.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textSecondary),
        ) {
            Text(
                text = GalleryFormat.deleteOriginals(count),
                style = DarkChamberTheme.typography.label,
            )
        }
    }
}

@Composable
private fun ItemGrid(
    items: List<GalleryItem>,
    gridState: LazyGridState,
    reordering: Boolean,
    now: Long,
    imageLoader: ImageLoader,
    bottomInset: Dp,
    selection: GallerySelection,
    onSelectItem: (ByteArray) -> Unit,
    onToggleItem: (ByteArray) -> Unit,
) {
    val spacing = DarkChamberTheme.spacing
    val arrival = tileArrival(DarkChamberTheme.motion, prefersReducedMotion(), reordering)

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = TILE_MIN_WIDTH),
        contentPadding = PaddingValues(
            start = spacing.spaceMd,
            end = spacing.spaceMd,
            top = spacing.spaceMd,
            // The bottom bar's measured height, so the last row scrolls clear of it instead of
            // parking underneath. It is a tile's *caption* that gets trapped — the name and size sit
            // below the thumbnail — so being a few dp short still costs the row its label.
            bottom = bottomInset + spacing.spaceMd,
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing.spaceSm),
        verticalArrangement = Arrangement.spacedBy(spacing.spaceSm),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Keyed by the object id, never by position. With newest-first ordering every existing tile
        // shifts down on each import, so an index key would tell Compose that every row changed —
        // animating the whole grid instead of the one tile that actually arrived.
        //
        // Putting the order in the key as well was tried, on the theory that a key the re-order could
        // not match would leave the grid nothing to chase. It does not: measured on a device, the
        // viewport still moved to the far end, so the move is not a key remap. It only cost every tile
        // being composed again on each order change. Holding the position is done explicitly instead
        // — see [rememberBrowsingScroll].
        items(items, key = { it.id.toListKey() }) { item ->
            ItemTile(
                item = item,
                now = now,
                imageLoader = imageLoader,
                selecting = selection.active,
                selected = selection.contains(item.id),
                onLongPress = { onSelectItem(item.id) },
                onTap = { onToggleItem(item.id) },
                modifier = Modifier.animateItem(
                    fadeInSpec = arrival.fadeIn?.let { tween(it) },
                    placementSpec = arrival.placement?.let { tween<IntOffset>(it) },
                    fadeOutSpec = arrival.fadeOut?.let { tween(it) },
                ),
            )
        }
    }
}

@Composable
private fun ItemTile(
    item: GalleryItem,
    now: Long,
    imageLoader: ImageLoader,
    selecting: Boolean,
    selected: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    val damaged = item.meta is GalleryItemMeta.Unreadable

    Column(
        modifier = modifier
            .fillMaxWidth()
            // The whole tile is the target, caption included: a thumbnail is a small thing to hit,
            // and the name under it is part of what the user is pointing at.
            //
            // A tap does nothing outside selecting mode. Opening an item is the viewers' work, and
            // wiring a tap to anything now would be inventing a behaviour that has to be taken back.
            .combinedClickable(
                onClick = { if (selecting) onTap() },
                onLongClick = onLongPress,
            ),
    ) {
        // The thumbnail's future home, reserved at its final size so images drop in without
        // relayout. Until then it carries a type badge derived from the MIME family.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(
                    color = if (damaged) colors.surface else colors.surfaceRaised,
                    shape = DarkChamberTheme.shapes.medium,
                )
                .border(
                    // Selected reads as a thicker accent frame, not a new shape: the slot keeps its
                    // exact size, so selecting cannot reflow the grid under the user's finger.
                    width = if (selected) TILE_SELECTED_BORDER else TILE_BORDER,
                    color = when {
                        selected -> colors.accent
                        damaged -> colors.danger
                        else -> colors.outline
                    },
                    shape = DarkChamberTheme.shapes.medium,
                ),
        ) {
            // `unverified` marks a preview whose bytes did not pass their integrity tag, which is
            // not the same as the item being damaged: the item's own bytes and metadata are intact
            // and it still opens. The mark says the preview could not be vouched for, and nothing
            // more than that (hard rule #6).
            val badge: @Composable (unverified: Boolean) -> Unit = { unverified ->
                // Centred by a Box of its own rather than by the slot it sits in. Coil's subcompose
                // slots lay their content out with `propagateMinConstraints = true`, so a bare Text
                // is stretched to the full tile and then draws its glyphs at the top-left of those
                // bounds — the alignment of the enclosing box never gets a say. This one fills the
                // slot deliberately and centres inside itself, which holds in both places the badge
                // is drawn.
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = when (val meta = item.meta) {
                            is GalleryItemMeta.Readable ->
                                if (unverified) "!" else GalleryFormat.typeBadge(meta.mime)
                            GalleryItemMeta.Unreadable -> "!"
                        },
                        style = DarkChamberTheme.typography.label,
                        color = if (damaged || unverified) colors.danger else colors.textSecondary,
                    )
                }
            }

            if (damaged) {
                // An item whose metadata will not open is not asked for a preview at all: its key
                // is gone with the rest of that blob, and the mark it already carries is the honest
                // rendering. Unchanged from before thumbnails existed.
                badge(false)
            } else {
                SubcomposeAsyncImage(
                    // Built fresh each composition on purpose, and not `remember`ed: the key would
                    // have to be the id, whose `equals` is identity, so a re-emitted listing would
                    // rebuild it regardless. What actually stops the request restarting is the
                    // model's own content-based equality — a new instance carrying the same id is
                    // equal to the old one, so Coil keeps the image it already has.
                    model = VaultThumbnailKey(item.id),
                    imageLoader = imageLoader,
                    // Decorative: the item's name is spelled out directly below this slot, and a
                    // screen reader announcing it twice helps nobody.
                    contentDescription = null,
                    // Crop rather than fit, matching the contact-sheet grid: a letterboxed tile in
                    // a dense grid reads as a gap.
                    contentScale = ContentScale.Crop,
                    // The badge covers everything that is not a drawn preview: an item with no
                    // thumbnail (the ordinary case for most of a vault), one whose preview failed
                    // to open, and the moment before a decrypt finishes. A spinner per tile would
                    // make a scroll flicker for work that takes a few milliseconds.
                    loading = { badge(false) },
                    error = {
                        // One failure among them is not ordinary. A preview whose bytes failed
                        // their integrity tag is a tampering signal, and drawing it as the same
                        // badge an audio file gets would be the silent rendering the error-handling
                        // rule forbids. Everything else here — an image Coil could not decode, a
                        // preview that would not read — leaves the tile exactly as it was.
                        // `.value`: the painter's state is a StateFlow, not a State. Casting the flow
                        // itself compiles — it is a cast between unrelated types the compiler will only
                        // warn about — and then always yields null, which silently turned this whole
                        // branch into the ordinary badge. The slot is composed only while the state *is*
                        // Error, so reading the current value here is right.
                        val cause = (painter.state.value as? AsyncImagePainter.State.Error)?.result?.throwable
                        badge(previewFailedIntegrity(cause))
                    },
                    modifier = Modifier.fillMaxSize().clip(DarkChamberTheme.shapes.medium),
                )
            }

            // **Which album this is in**, on the one listing that mixes filed and unfiled items —
            // and nowhere else, because the label itself is absent elsewhere: inside an album every
            // item is in that album and in Unfiled none has one. The rendering has no scope to
            // check; the listing stage decides it, and `ObserveGalleryAlbumsTest` pins it.
            //
            // Nothing at all on an unfiled item. An absence is the honest marker for "in no album",
            // and a badge on every tile would be noise on the common case.
            //
            // Inside the media slot and in the corner **opposite the check mark**, so a filed item
            // that is also selected shows both without one covering the other.
            item.album?.let { album ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(spacing.spaceXs)
                        // Capped, and the only reason this is a Box around a Row: the Row wraps its
                        // content so the badge is as small as the name allows, while this bounds how
                        // far a long one may grow — short of the check mark's corner.
                        .fillMaxWidth(ALBUM_BADGE_MAX_FRACTION),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.spaceXxs),
                        modifier = Modifier
                            .background(
                                color = colors.surface,
                                // Rounded, never a circle: that radius is the aperture's alone
                                // (docs/DESIGN.md §4).
                                shape = DarkChamberTheme.shapes.small,
                            )
                            .border(
                                width = TILE_BORDER,
                                color = colors.outline,
                                shape = DarkChamberTheme.shapes.small,
                            )
                            .padding(horizontal = spacing.spaceXxs, vertical = ALBUM_BADGE_INSET),
                    ) {
                        AlbumMark(
                            // Named for a reader, unlike the album grid's badge: there the word
                            // "Album" sits beside the mark, while here the only other text is the
                            // album's own name.
                            contentDescription = GalleryFormat.inAlbum(),
                            tint = colors.textSecondary,
                            modifier = Modifier.size(ALBUM_BADGE_MARK),
                        )
                        Text(
                            text = album.label(),
                            style = DarkChamberTheme.typography.caption,
                            // The danger role when the album's name would not open, as everywhere
                            // else that renders one — a badge is small, not a reason to say it
                            // quietly.
                            color = if (album is AlbumTileName.Unreadable) colors.danger else colors.textSecondary,
                            maxLines = 1,
                            // Truncated inside the badge rather than spanning the tile.
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Inside the media slot, which already reserves this square — **not** in the tile's own
            // corner. A badge drawn into the tile corner is the bug an earlier release shipped: the
            // tile is a Column of slot plus two lines of text, so its corners are over the caption,
            // and a corner-aligned overlay landed on the file name of the row below.
            //
            // Only while selecting: an unselected tile outside the mode carries no mark at all, so
            // the ordinary grid is unchanged.
            if (selecting) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(spacing.spaceXs)
                        .size(SELECTION_MARK)
                        .background(
                            color = if (selected) colors.accent else colors.surface.copy(alpha = MARK_UNSELECTED_ALPHA),
                            // Rounded, never a circle: the full-circle radius is the aperture's
                            // alone (docs/DESIGN.md §4), and a tick in a circle would read as one.
                            shape = DarkChamberTheme.shapes.small,
                        )
                        .border(
                            width = TILE_BORDER,
                            color = if (selected) colors.accent else colors.outline,
                            shape = DarkChamberTheme.shapes.small,
                        ),
                ) {
                    if (selected) {
                        Text(
                            text = "✓",
                            style = DarkChamberTheme.typography.label,
                            color = colors.onAccent,
                        )
                    }
                }
            }
        }

        Text(
            text = item.meta.label(),
            style = DarkChamberTheme.typography.body,
            color = if (damaged) colors.danger else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = spacing.spaceXs),
        )

        Text(
            text = "${GalleryFormat.size(item.size)} · ${GalleryFormat.age(item.createdAt, now)}",
            style = DarkChamberTheme.typography.caption,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * An empty listing, which is five different situations — see [GalleryFormat.emptyListing]. The same
 * screen serves all of them, so the copy is the only thing that can tell them apart, and two of the
 * five are not about the vault's contents at all: the grid could not be read, or a search matched
 * nothing in a listing that is not empty.
 *
 * Which of the five wins is decided in the formatter, and which state each of its questions is
 * answered from is decided in [emptyListingMessage] — so both halves are something a test can assert
 * on, and nothing is left here but drawing.
 */
@Composable
private fun EmptyListing(message: String) {
    CenteredMessage(message, showSpinner = false)
}

@Composable
private fun CenteredMessage(text: String, showSpinner: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showSpinner) {
                CircularProgressIndicator(color = DarkChamberTheme.colors.accent)
            }
            Text(
                text = text,
                style = DarkChamberTheme.typography.body,
                color = DarkChamberTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(DarkChamberTheme.spacing.spaceMd),
            )
        }
    }
}

@Composable
private fun ErrorBanner(error: GalleryError, onDismiss: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.spaceMd, vertical = spacing.spaceXs)
            .background(color = colors.sealedContainer, shape = DarkChamberTheme.shapes.medium)
            .padding(start = spacing.spaceMd),
    ) {
        Text(
            text = when (error) {
                GalleryError.ImportFailed -> "That file couldn't be added to the vault."
                GalleryError.ListingFailed -> "The vault's index couldn't be read. Your files are still encrypted."
                // Says where they are rather than only what failed: the user is looking at a selection
                // that did not move, and the one thing they need to know is that nothing was lost.
                GalleryError.FilingFailed -> "Those items couldn't be moved. They are where they were."
            },
            style = DarkChamberTheme.typography.body,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f).padding(vertical = spacing.spaceXs),
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss", style = DarkChamberTheme.typography.label, color = colors.accent)
        }
    }
}

/** Tall enough for several albums, short enough to leave the dialog's buttons on screen. */
private val CHOOSER_LIST_MAX = 260.dp

/** Wide enough for a name to read at two columns on a phone, narrow enough for three on a tablet. */
private val TILE_MIN_WIDTH = 132.dp
private val TILE_BORDER = 1.dp

/** The selected frame. Thicker than [TILE_BORDER] so selection is legible without colour alone. */
private val TILE_SELECTED_BORDER = 2.dp

/** The tick's box, inside the media slot. Large enough to read, small enough not to cover a preview. */
private val SELECTION_MARK = 22.dp

/** The unselected mark sits over a thumbnail, so it needs to be visible without hiding it. */
private const val MARK_UNSELECTED_ALPHA = 0.6f

/**
 * How much of the slot a filed item's album badge may take.
 *
 * Two thirds, so a long album name truncates well before the check mark's corner — the two are on
 * opposite sides, and a badge allowed the full width would slide under the tick the moment selecting
 * mode opened.
 */
private const val ALBUM_BADGE_MAX_FRACTION = 0.66f

/** The badge's mark, sized to the caption beside it rather than to an icon's own 24 dp. */
private val ALBUM_BADGE_MARK = 12.dp

/** Vertical breathing room, so the name does not sit on the badge's own border. */
private val ALBUM_BADGE_INSET = 2.dp
