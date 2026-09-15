package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.core.designsystem.prefersReducedMotion
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState

/**
 * The vault gallery (docs/DESIGN.md). Reads theme **roles** only — no raw hex, no hardcoded colors —
 * and leaves the full-circle radius to the aperture, so the import affordance is a rounded extended
 * FAB rather than a circle.
 *
 * Tiles reserve a square media slot at the size a thumbnail will occupy, so cycle 2 can drop images
 * in without relayout. Tapping a tile does nothing yet; viewers arrive with the seekable read path.
 */
@Composable
internal fun GalleryScreen(
    state: GalleryState,
    now: Long,
    onAddPhotos: () -> Unit,
    onAddFiles: () -> Unit,
    onCancelImport: () -> Unit,
    onAcknowledgeImport: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = DarkChamberTheme.spacing

    // No insets here: the host applies them once for the whole unlocked subtree, so a screen that
    // added its own would inset twice.
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            state.error?.let { error ->
                ErrorBanner(error = error, onDismiss = onDismissError)
            }

            when {
                state.loading -> CenteredMessage("Opening the vault…", showSpinner = true)
                state.items.isEmpty() -> EmptyVault()
                else -> ItemGrid(items = state.items, now = now)
            }
        }

        // Bottom of the screen: the import affordances, or — while a batch runs — what it is
        // doing and how to stop it. One at a time: offering "Add photos" mid-import would invite a
        // second batch the coordinator would only refuse.
        Box(
            contentAlignment = Alignment.BottomEnd,
            modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth().padding(spacing.spaceLg),
        ) {
            when (val batch = state.batch) {
                is ImportState.Running -> ImportProgressCard(batch = batch, onCancel = onCancelImport)
                ImportState.Picking -> ImportNoticeCard(text = "Choose what to add…")
                is ImportState.Finished -> ImportSummaryCard(batch = batch, onDismiss = onAcknowledgeImport)
                ImportState.Idle -> ImportActions(onAddPhotos = onAddPhotos, onAddFiles = onAddFiles)
            }
        }
    }
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
            text = KEEP_OPEN_NOTICE,
            style = DarkChamberTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = spacing.spaceXs),
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) {
                // Says what cancelling actually costs: the files already in the vault stay there.
                Text("Stop (keeps what's added)", style = DarkChamberTheme.typography.label, color = colors.accent)
            }
        }
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
private fun ImportSummaryCard(batch: ImportState.Finished, onDismiss: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(color = colors.sealedContainer, shape = DarkChamberTheme.shapes.large)
            .padding(start = spacing.spaceMd),
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = spacing.spaceXs)) {
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
        }
        TextButton(onClick = onDismiss) {
            Text("Done", style = DarkChamberTheme.typography.label, color = colors.accent)
        }
    }
}

@Composable
private fun ItemGrid(items: List<GalleryItem>, now: Long) {
    val spacing = DarkChamberTheme.spacing
    val arrival = tileArrival(DarkChamberTheme.motion, prefersReducedMotion())
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TILE_MIN_WIDTH),
        contentPadding = PaddingValues(
            start = spacing.spaceMd,
            end = spacing.spaceMd,
            top = spacing.spaceMd,
            // Room for the FAB, so the last row is never trapped underneath it.
            bottom = spacing.spaceXxl + spacing.spaceLg,
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
private fun ItemTile(item: GalleryItem, now: Long, modifier: Modifier = Modifier) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    val damaged = item.meta is GalleryItemMeta.Unreadable

    Column(modifier = modifier.fillMaxWidth()) {
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
                    width = TILE_BORDER,
                    color = if (damaged) colors.danger else colors.outline,
                    shape = DarkChamberTheme.shapes.medium,
                ),
        ) {
            Text(
                text = when (val meta = item.meta) {
                    is GalleryItemMeta.Readable -> GalleryFormat.typeBadge(meta.mime)
                    GalleryItemMeta.Unreadable -> "!"
                },
                style = DarkChamberTheme.typography.label,
                color = if (damaged) colors.danger else colors.textSecondary,
            )
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

@Composable
private fun EmptyVault() {
    CenteredMessage("Nothing in the vault yet.\nAdd a file to encrypt it.", showSpinner = false)
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

/** Wide enough for a name to read at two columns on a phone, narrow enough for three on a tablet. */
private val TILE_MIN_WIDTH = 132.dp
private val TILE_BORDER = 1.dp
