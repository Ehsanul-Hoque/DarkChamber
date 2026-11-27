package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.serviceLoaderEnabled
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState

/**
 * [GalleryScreen] for a UI test: [state] as given, every callback a no-op except the ones a test hands
 * in. A test passes only the callbacks whose firing it asserts, so a tap reaching the wrong one shows.
 *
 * The image loader is built from the composition's own context, with no disk cache and no service
 * loader — the same two settings the vault's loader keeps — and nothing to fetch with: a test drawing
 * tiles sees each one's no-preview state.
 */
@Composable
internal fun TestGalleryScreen(
    state: GalleryState,
    onAcknowledgeImport: () -> Unit = {},
    onRequestOriginalDelete: () -> Unit = {},
) {
    val context = LocalPlatformContext.current
    val imageLoader =
        remember(context) {
            ImageLoader
                .Builder(context)
                .serviceLoaderEnabled(false)
                .diskCache(null)
                .build()
        }
    GalleryScreen(
        state = state,
        now = 0L,
        imageLoader = imageLoader,
        onChooseOrder = {},
        onOpenSearch = {},
        onSearchFocus = {},
        onEditSearch = {},
        onCloseSearch = {},
        onAddPhotos = {},
        onAddFiles = {},
        onOpenCamera = {},
        onCancelImport = {},
        onAcknowledgeImport = onAcknowledgeImport,
        onRequestOriginalDelete = onRequestOriginalDelete,
        onConfirmOriginalDelete = {},
        onKeepOriginals = {},
        onDismissError = {},
        onSelectItem = {},
        onTapItem = {},
        onReveal = {},
        onSelectAll = {},
        onClearSelection = {},
        onStartFiling = {},
        onChooseAlbum = {},
        onCancelFiling = {},
        onUnfileSelection = {},
        onStartFilingCreate = {},
        onEditFilingAlbumName = {},
        onConfirmFilingCreate = {},
        onCancelFilingCreate = {},
        onAcknowledgeFiling = {},
        onRequestDeleteSelection = {},
        onConfirmDeleteSelection = {},
        onCancelDeleteSelection = {},
        onAcknowledgeDeletion = {},
        onStartAlbumRename = {},
        onEditAlbumName = {},
        onConfirmAlbumRename = {},
        onCancelAlbumRename = {},
        onRequestAlbumDelete = {},
        onConfirmAlbumDelete = {},
        onCancelAlbumDelete = {},
        onLeaveAlbum = {},
    )
}
