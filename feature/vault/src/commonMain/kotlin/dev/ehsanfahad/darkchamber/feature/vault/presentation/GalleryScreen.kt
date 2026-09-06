package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState

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
    onImport: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = DarkChamberTheme.spacing

    Box(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
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

        ExtendedFloatingActionButton(
            onClick = onImport,
            containerColor = DarkChamberTheme.colors.accent,
            contentColor = DarkChamberTheme.colors.onAccent,
            // Rounded, never a circle: the full-circle radius belongs to the aperture (DESIGN.md §7).
            shape = DarkChamberTheme.shapes.large,
            modifier = Modifier.align(Alignment.BottomEnd).padding(spacing.spaceLg),
        ) {
            Text(text = if (state.importing) "Importing…" else "Add file", style = DarkChamberTheme.typography.label)
        }
    }
}

@Composable
private fun ItemGrid(items: List<GalleryItem>, now: Long) {
    val spacing = DarkChamberTheme.spacing
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
        items(items, key = { it.id.toHexKey() }) { item -> ItemTile(item = item, now = now) }
    }
}

@Composable
private fun ItemTile(item: GalleryItem, now: Long) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    val damaged = item.meta is GalleryItemMeta.Unreadable

    Column(modifier = Modifier.fillMaxWidth()) {
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

/** Wide enough for a name to read at two columns on a phone, narrow enough for three on a tablet. */
private val TILE_MIN_WIDTH = 132.dp
private val TILE_BORDER = 1.dp
