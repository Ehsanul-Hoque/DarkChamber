package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
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
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.core.designsystem.prefersReducedMotion
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDelete
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumState
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.Filing
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.api.GallerySelection
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.OriginalDelete

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

    // No insets here: the host applies them once for the whole unlocked subtree, so a screen that
    // added its own would inset twice.
    val album = state.album

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            state.error?.let { error ->
                ErrorBanner(error = error, onDismiss = onDismissError)
            }

            // An album's own heading, with the two things that can be done to the album itself. Only
            // ever present on an album-scoped screen: the whole vault cannot be renamed, and Unfiled
            // is a view rather than a thing.
            if (album is AlbumState.Present) {
                AlbumHeader(
                    name = album.name,
                    // Nothing to rename when the name will not open, so the action is absent rather
                    // than present and dead — but deleting stays, because a damaged album is the one
                    // most likely to want deleting.
                    onRename = onStartAlbumRename.takeIf { album.name is AlbumTileName.Readable },
                    onDelete = onRequestAlbumDelete,
                )
            }

            when {
                // Ahead of loading and of the grid: an album that is no longer there has nothing to
                // list, and a spinner or an "empty album" message would both be lying about why.
                album is AlbumState.Gone -> MissingAlbumNotice(onLeave = onLeaveAlbum)
                state.loading -> CenteredMessage("Opening the vault…", showSpinner = true)
                state.items.isEmpty() -> EmptyListing(state.scope)
                else -> ItemGrid(
                    items = state.items,
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
                    // belongs to the cycle that owns it.
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
                    onDismiss = onAcknowledgeImport,
                    onDeleteOriginals = onRequestOriginalDelete,
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
                count = (batch as ImportState.Finished).deletableOriginals,
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
                    itemCount = state.items.size,
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
                    items(choosing.albums, key = { it.id.toChoiceKey() }) { choice ->
                        Text(
                            text = when (val name = choice.name) {
                                is AlbumTileName.Readable -> name.value
                                AlbumTileName.Unreadable -> "Can't be verified"
                            },
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
 * An album's heading: what it is called, and the two things that can be done to the album itself.
 *
 * The name is drawn from the listing rather than from the screen that led here, so a rename shows up
 * on the heading without this screen being told about it.
 *
 * [onRename] is null for an album whose name will not open — absent rather than disabled, because a
 * dead control invites a tap and explains nothing. Deleting stays available there on purpose: an album
 * nobody can read the name of is the one most likely to want removing, and doing so destroys none of
 * its contents.
 */
@Composable
private fun AlbumHeader(name: AlbumTileName, onRename: (() -> Unit)?, onDelete: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.spaceMd, vertical = spacing.spaceXs),
    ) {
        Text(
            text = when (name) {
                is AlbumTileName.Readable -> name.value
                // The same wording the tile carries, for the same reason: we know the name did not
                // verify, not whether it was tampered with or merely corrupted (hard rule #6).
                AlbumTileName.Unreadable -> "Can't be verified"
            },
            style = DarkChamberTheme.typography.title,
            color = if (name is AlbumTileName.Unreadable) colors.danger else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        onRename?.let { rename ->
            TextButton(onClick = rename) {
                Text(
                    text = GalleryFormat.renameAlbum(),
                    style = DarkChamberTheme.typography.label,
                    color = colors.accent,
                )
            }
        }
        TextButton(onClick = onDelete) {
            // Not the danger role. Deleting an album destroys nothing, and colouring it as though it
            // did would be the honesty rule broken in the other direction — frightening someone away
            // from a harmless action (hard rule #6).
            Text(
                text = GalleryFormat.deleteAlbum(),
                style = DarkChamberTheme.typography.label,
                color = colors.accent,
            )
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
    itemCount: Int,
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
 * The selection bar: how many are selected, and the two ways to change that wholesale.
 *
 * It replaces the import affordances rather than sitting beside them — one bottom bar at a time, as
 * the batch states already are. No action buttons yet: filing arrives with the album surface, and
 * shredding belongs to the cycle that owns shredding and its own disclosure, so offering either here
 * would be offering something that cannot happen.
 *
 * The count is the whole message. It is also the one honest thing to say while the selection is
 * being intersected against a listing that can change underneath it.
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

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = GalleryFormat.selectedCount(count),
            style = DarkChamberTheme.typography.label,
            color = DarkChamberTheme.colors.textPrimary,
        )
        Spacer(Modifier.width(spacing.spaceMd))
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
            text = if (batch.stopping) STOPPING_NOTICE else KEEP_OPEN_NOTICE,
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
            text = KEEP_OPEN_NOTICE,
            style = DarkChamberTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = spacing.spaceXs),
        )
    }
}

/**
 * What a finished batch amounts to. Says how many could not be read rather than implying everything
 * worked, and never claims a reason it does not have (hard rule #6).
 */
@Composable
private fun ImportSummaryCard(
    batch: ImportState.Finished,
    onDismiss: () -> Unit,
    onDeleteOriginals: () -> Unit,
) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.sealedContainer, shape = DarkChamberTheme.shapes.large)
            .padding(start = spacing.spaceMd),
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

        // Beside Done rather than under the summary text, where it read as a stray line of the
        // message. Offered only when there is something to offer, and gone once it has been
        // answered: nothing deletable is the ordinary case — every photo-library item, every iOS
        // item, most cloud documents — so it has to be *absent*, not a disabled control owing an
        // explanation. The common case is therefore the message and Done alone, the shape this card
        // has always had.
        if (batch.deletableOriginals > 0 && batch.originalDelete == OriginalDelete.NotAsked) {
            TextButton(onClick = onDeleteOriginals) {
                // Muted, and left of the dismissal: this only opens the disclosure, so it is not
                // the destructive step and must not dress like one — but it still must not be
                // mistaken for Done, which keeps the accent.
                Text(
                    text = GalleryFormat.deleteOriginals(batch.deletableOriginals),
                    style = DarkChamberTheme.typography.label,
                    color = colors.textSecondary,
                )
            }
            // A gap, so the two are not one continuous strip of tappable text.
            Spacer(modifier = Modifier.width(spacing.spaceSm))
        }

        TextButton(onClick = onDismiss) {
            Text("Done", style = DarkChamberTheme.typography.label, color = colors.accent)
        }
    }
}

@Composable
private fun ItemGrid(
    items: List<GalleryItem>,
    now: Long,
    imageLoader: ImageLoader,
    bottomInset: Dp,
    selection: GallerySelection,
    onSelectItem: (ByteArray) -> Unit,
    onToggleItem: (ByteArray) -> Unit,
) {
    val spacing = DarkChamberTheme.spacing
    val arrival = tileArrival(DarkChamberTheme.motion, prefersReducedMotion())
    LazyVerticalGrid(
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
        items(items, key = { it.id.toHexKey() }) { item ->
            ItemTile(
                item = item,
                now = now,
                imageLoader = imageLoader,
                selecting = selection.active,
                selected = selection.contains(item.id),
                onLongPress = { onSelectItem(item.id) },
                onTap = { onToggleItem(item.id) },
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(arrival.fadeIn),
                    placementSpec = arrival.placement?.let { tween<IntOffset>(it) },
                    fadeOutSpec = tween(arrival.fadeOut),
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
            // A tap does nothing outside selecting mode. Opening an item is the viewers' cycle, and
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
                        val cause = (painter.state as? AsyncImagePainter.State.Error)?.result?.throwable
                        badge(previewFailedIntegrity(cause))
                    },
                    modifier = Modifier.fillMaxSize().clip(DarkChamberTheme.shapes.medium),
                )
            }

            // Inside the media slot, which already reserves this square — **not** in the tile's own
            // corner. A badge drawn into the tile corner is the bug a previous cycle shipped: the
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
            text = when (val meta = item.meta) {
                is GalleryItemMeta.Readable -> meta.name
                // Honest, and no more than the threat model allows: we know the metadata did not
                // verify, not whether it was tampered with or merely corrupted (hard rule #6).
                GalleryItemMeta.Unreadable -> "Can't be verified"
            },
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
 * An empty listing, which is three different situations — see [GalleryFormat.emptyListing]. The same
 * screen serves all of them, so the copy is the only thing that can tell them apart.
 */
@Composable
private fun EmptyListing(scope: GalleryScope) {
    CenteredMessage(GalleryFormat.emptyListing(scope), showSpinner = false)
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

// Stable list key. The id is opaque bytes, so hex is just an identity — never content.
private fun ByteArray.toHexKey(): String = joinToString("") { byte ->
    val v = byte.toInt() and 0xFF
    HEX[v shr 4].toString() + HEX[v and 0x0F]
}

private const val HEX = "0123456789abcdef"

/**
 * The load-bearing sentence of this screen (spec §4.4). A lock or a background ends a batch, and
 * afterwards there is nobody left to tell — the state died with the session. Warning beforehand is
 * the only chance there is, so it stays visible for the whole import, not just at the start.
 */
private const val KEEP_OPEN_NOTICE =
    "Keep DarkChamber open — locking the vault or leaving the app stops the import."

/**
 * Shown from the moment Stop is tapped until the batch actually ends.
 *
 * Says a stop *may take a moment* rather than naming what it is waiting for, because that now
 * depends on where the stop lands. Waiting for a provider to hand the file over is abandoned at
 * once; part-way through the encrypt the stop takes effect on the next read of the payload, which is
 * kilobytes away but still not instant (docs/THREAT_MODEL.md's note that a stop is cooperative).
 *
 * It used to say "once the current file finishes reading", which described the longer of those two
 * waits — correct when a blocked fetch could not be interrupted, and an understatement of the stop
 * now that it can be. Promising an instant stop would be the opposite error (hard rule #6), so it
 * promises neither. The second half is the part that stops the wait feeling like a loss.
 */
private const val STOPPING_NOTICE =
    "Stopping — this may take a moment. Everything already added stays in the vault."

/**
 * Hex, for the chooser's stable list keys: a `ByteArray` is not usable as one — its equality is identity.
 */
private fun ByteArray.toChoiceKey(): String =
    joinToString("") { byte ->
        val value = byte.toInt() and 0xFF
        "${CHOICE_HEX[value shr 4]}${CHOICE_HEX[value and 0x0F]}"
    }

private const val CHOICE_HEX = "0123456789abcdef"

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
