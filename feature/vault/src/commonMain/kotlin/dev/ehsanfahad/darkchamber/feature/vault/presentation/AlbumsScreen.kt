package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraft
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTile
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumsError
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumsState

/**
 * The album grid.
 *
 * Deliberately plainer than the item grid: albums are few, nothing here animates on arrival, and the
 * one affordance makes an album rather than importing a file — an album list is something a user
 * returns to rather than watches fill.
 */
@Composable
internal fun AlbumsScreen(
    state: AlbumsState,
    imageLoader: ImageLoader,
    onOpenAlbum: (ByteArray) -> Unit,
    onOpenUnfiled: () -> Unit,
    onStartCreate: () -> Unit,
    onEditAlbumName: (String) -> Unit,
    onConfirmCreate: () -> Unit,
    onCancelCreate: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = DarkChamberTheme.spacing
    val density = LocalDensity.current

    // The affordance floats over the grid, so the grid has to know how tall it is — measured rather
    // than guessed at, for the reason the gallery's bar is: the button grows with the OS text size,
    // and a constant that fits it at the default setting hides the last row at a larger one.
    var barHeight by remember { mutableStateOf(0.dp) }

    // No insets here: the host applies them once for the whole unlocked subtree.
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            state.error?.let { error ->
                AlbumsErrorBanner(error = error, onDismiss = onDismissError)
            }

            when {
                state.loading -> CenteredNotice("Reading your albums…")
                // No albums is not an empty screen: Unfiled still leads somewhere, because files
                // imported before any album existed are exactly what a user arriving here is looking
                // for. So the grid renders either way and the explanation sits above it.
                else -> AlbumGrid(
                    albums = state.albums,
                    imageLoader = imageLoader,
                    bottomInset = barHeight,
                    onOpenAlbum = onOpenAlbum,
                    onOpenUnfiled = onOpenUnfiled,
                )
            }
        }

        // Always offered, including while the listing is still loading or has failed: creating an
        // album does not depend on reading the existing ones, and a surface that hides its one action
        // behind a catalog error leaves the user with a screen that can only be left.
        Box(
            contentAlignment = Alignment.BottomEnd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth()
                .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
                .padding(spacing.spaceLg),
        ) {
            ExtendedFloatingActionButton(
                onClick = onStartCreate,
                containerColor = DarkChamberTheme.colors.accent,
                contentColor = DarkChamberTheme.colors.onAccent,
                // Rounded, never a circle: the full-circle radius belongs to the aperture
                // (docs/DESIGN.md §4).
                shape = DarkChamberTheme.shapes.large,
            ) {
                Text(text = GalleryFormat.newAlbum(), style = DarkChamberTheme.typography.label)
            }
        }

        state.draft?.let { draft ->
            NewAlbumDialog(
                draft = draft,
                onNameChange = onEditAlbumName,
                onConfirm = onConfirmCreate,
                onCancel = onCancelCreate,
            )
        }
    }
}

/**
 * Name the new album.
 *
 * Dismissable by back and by tapping outside, unlike the import disclosure: there is nothing here a
 * user has to be told before they can act, and the worst a stray dismissal costs is a name worth
 * retyping. **Except while the create runs** — then neither gesture is honoured, because the vault is
 * already writing and a dialog that vanished would be claiming otherwise.
 */
@Composable
private fun NewAlbumDialog(
    draft: AlbumDraft,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = !draft.saving,
            dismissOnClickOutside = !draft.saving,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = colors.surface, shape = DarkChamberTheme.shapes.large)
                .padding(spacing.spaceLg),
        ) {
            Text(
                text = GalleryFormat.newAlbum(),
                style = DarkChamberTheme.typography.title,
                color = colors.textPrimary,
            )

            OutlinedTextField(
                value = draft.name,
                onValueChange = onNameChange,
                label = { Text(GalleryFormat.albumNameLabel()) },
                singleLine = true,
                // The field is the whole dialog, so the keyboard's action is the dialog's: a user who
                // typed a name and pressed Done meant to create it.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                isError = draft.problem != null,
                enabled = !draft.saving,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceMd),
            )

            draft.problem?.let { problem ->
                Text(
                    text = GalleryFormat.albumDraftProblem(problem),
                    style = DarkChamberTheme.typography.caption,
                    color = colors.danger,
                    modifier = Modifier.padding(top = spacing.spaceXs),
                )
            }

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceMd),
            ) {
                TextButton(onClick = onCancel, enabled = !draft.saving) {
                    Text(
                        text = "Cancel",
                        style = DarkChamberTheme.typography.label,
                        color = if (draft.saving) colors.textSecondary else colors.textPrimary,
                    )
                }
                TextButton(onClick = onConfirm, enabled = !draft.saving) {
                    // Its own past tense while the write runs: it is the only thing on screen saying
                    // why the dialog stopped responding. Nothing destructive happens here, so neither
                    // button takes the danger role.
                    Text(
                        text = if (draft.saving) "Creating…" else "Create",
                        style = DarkChamberTheme.typography.label,
                        color = if (draft.saving) colors.textSecondary else colors.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<AlbumTile>,
    imageLoader: ImageLoader,
    bottomInset: Dp,
    onOpenAlbum: (ByteArray) -> Unit,
    onOpenUnfiled: () -> Unit,
) {
    val spacing = DarkChamberTheme.spacing
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = ALBUM_MIN_WIDTH),
        contentPadding = PaddingValues(
            start = spacing.spaceMd,
            end = spacing.spaceMd,
            top = spacing.spaceMd,
            // Room for the floating affordance, so the last row can be scrolled clear of it.
            bottom = spacing.spaceMd + bottomInset,
        ),
        horizontalArrangement = Arrangement.spacedBy(spacing.spaceSm),
        verticalArrangement = Arrangement.spacedBy(spacing.spaceSm),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (albums.isEmpty()) {
            // Spans the row, above the Unfiled entry: a grid with one cell in it and no explanation
            // teaches nothing about what albums are for.
            item(span = { GridItemSpan(maxLineSpan) }, key = NO_ALBUMS_KEY) {
                Text(
                    text = GalleryFormat.noAlbums(),
                    style = DarkChamberTheme.typography.body,
                    color = DarkChamberTheme.colors.textSecondary,
                    modifier = Modifier.padding(bottom = spacing.spaceSm),
                )
            }
        }

        // **Unfiled leads the grid, and is not an album.** Its own cell rather than a synthetic
        // AlbumTile: a tile would need an id, and an invented one is a lie every later surface would
        // have to keep — the rename and delete actions would have to learn to exclude it, and
        // whichever forgot would offer to rename a view.
        item(key = UNFILED_KEY) {
            UnfiledTileView(onOpen = onOpenUnfiled)
        }

        // Keyed by the album id, never by position: the grid is alphabetical, so creating or
        // renaming an album moves everything after it, and an index key would tell Compose that
        // every tile changed.
        items(albums, key = { it.id.toAlbumKey() }) { album ->
            AlbumTileView(album = album, imageLoader = imageLoader, onOpen = { onOpenAlbum(album.id) })
        }
    }
}

/**
 * The entry to everything not filed.
 *
 * **Carries no count**, deliberately: the album listing does not have one, and inventing a query for
 * it is a change to the catalog this screen does not need. A number that might be stale is worse
 * than no number, and tapping through answers the question exactly.
 *
 * It looks like a tile and is not one: no cover, no name to edit, and no badge saying "Album",
 * because it is not an album.
 */
@Composable
private fun UnfiledTileView(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(color = colors.surface, shape = DarkChamberTheme.shapes.medium)
                .border(
                    width = ALBUM_BORDER,
                    color = colors.outline,
                    shape = DarkChamberTheme.shapes.medium,
                ),
        ) {
            Text(
                text = "Unfiled",
                style = DarkChamberTheme.typography.label,
                color = colors.textSecondary,
            )
        }

        Text(
            text = "Unfiled",
            style = DarkChamberTheme.typography.body,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = spacing.spaceXs),
        )

        Text(
            text = "Not in an album",
            style = DarkChamberTheme.typography.caption,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

/**
 * One album: what is in it, what it is called, and how much.
 *
 * The cover is the newest item's preview, which keeps itself current and needs no state of its own.
 * An album whose newest items have no preview falls back to the same neutral slot an item tile with
 * no preview shows — nothing is invented to fill it.
 */
@Composable
private fun AlbumTileView(
    album: AlbumTile,
    imageLoader: ImageLoader,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    val damaged = album.name is AlbumTileName.Unreadable

    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)) {
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
                    width = ALBUM_BORDER,
                    color = if (damaged) colors.danger else colors.outline,
                    shape = DarkChamberTheme.shapes.medium,
                ),
        ) {
            val cover = album.coverItemId
            if (cover == null) {
                // Nothing in the album, or nothing in it with a preview. The slot stays as it is —
                // the badge below already says what this tile is.
                Text(
                    text = if (damaged) "!" else "",
                    style = DarkChamberTheme.typography.label,
                    color = colors.danger,
                )
            } else {
                SubcomposeAsyncImage(
                    // The same key an item tile uses, with the cover item's id: an album's cover is
                    // one of its items, so it resolves through the one encrypted-preview loader
                    // rather than a second path that would have to be kept honest separately.
                    model = VaultThumbnailKey(cover),
                    imageLoader = imageLoader,
                    // Decorative: the album's name is spelled out directly below.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // No spinner and no mark while it loads or if it fails: a cover is decoration,
                    // and an album with an unreadable *preview* is not a damaged album. Saying
                    // otherwise would claim something about the album that is not true.
                    loading = {},
                    error = {},
                    modifier = Modifier.fillMaxSize().clip(DarkChamberTheme.shapes.medium),
                )
            }

            // **The album marker.** Inside the media slot's own corner — not the tile's, which is
            // over the caption: that is the bug a previous cycle shipped. A new component rather
            // than a reuse of the item tile's badge, which is centred in the slot as the no-preview
            // fallback; this one sits on top of a drawn preview and has to read against it.
            //
            // It says "this is an album" and nothing else. No padlock and no shield: an album
            // organises and does not protect, and a tile implying otherwise is the easiest way to
            // break the honesty rule by accident (hard rule #6).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(spacing.spaceXs)
                    .background(
                        color = colors.surface,
                        // Rounded, never a circle: the full-circle radius belongs to the aperture
                        // alone (docs/DESIGN.md §4).
                        shape = DarkChamberTheme.shapes.small,
                    )
                    .border(
                        width = ALBUM_BORDER,
                        color = colors.outline,
                        shape = DarkChamberTheme.shapes.small,
                    )
                    .padding(horizontal = spacing.spaceXs),
            ) {
                Text(
                    text = "Album",
                    style = DarkChamberTheme.typography.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }

        Text(
            text = when (val name = album.name) {
                is AlbumTileName.Readable -> name.value
                // The same wording an item whose metadata will not open carries, and for the same
                // reason: we know the name did not verify, not whether it was tampered with or
                // merely corrupted (hard rule #6).
                AlbumTileName.Unreadable -> "Can't be verified"
            },
            style = DarkChamberTheme.typography.body,
            color = if (damaged) colors.danger else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = spacing.spaceXs),
        )

        Text(
            text = GalleryFormat.albumSubtitle(album.itemCount),
            style = DarkChamberTheme.typography.caption,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun CenteredNotice(text: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Text(
            text = text,
            style = DarkChamberTheme.typography.body,
            color = DarkChamberTheme.colors.textSecondary,
        )
    }
}

/**
 * A catalog that would not read, said plainly. The albums may exist; this screen could not list
 * them, and claiming the user has none would be the fail-closed rule's exact target.
 */
@Composable
private fun AlbumsErrorBanner(error: AlbumsError, onDismiss: () -> Unit) {
    val colors = DarkChamberTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .clickable(onClick = onDismiss)
            .padding(DarkChamberTheme.spacing.spaceMd),
    ) {
        Text(
            text = when (error) {
                AlbumsError.ListingFailed -> "Couldn't read your albums. Tap to dismiss."
            },
            style = DarkChamberTheme.typography.body,
            color = colors.danger,
        )
    }
}

/** Hex, for the grid's stable keys: a `ByteArray` is not usable as one — its equality is identity. */
private fun ByteArray.toAlbumKey(): String =
    joinToString("") { byte ->
        val value = byte.toInt() and 0xFF
        "${HEX_DIGITS[value shr 4]}${HEX_DIGITS[value and 0x0F]}"
    }

private const val HEX_DIGITS = "0123456789abcdef"

/** Wider than an item tile: an album carries a name and a count, and few of them fit badly. */
private val ALBUM_MIN_WIDTH = 150.dp

private val ALBUM_BORDER = 1.dp

// Stable keys for the two cells that are not albums. Prefixed so they can never collide with an
// album's hex id, which is only ever the digits 0-9 and a-f.
private const val UNFILED_KEY = "entry:unfiled"
private const val NO_ALBUMS_KEY = "notice:no-albums"
