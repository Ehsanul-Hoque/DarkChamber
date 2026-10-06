package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import coil3.ImageLoader
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultAlbumsComponent

/**
 * Render a [VaultAlbumsComponent]. The feature's second public composable, alongside the gallery's.
 *
 * The [imageLoader] is the session's, for the same reason the gallery's is: album covers are
 * decrypted previews held in memory, and locking has to be able to empty that cache even while the
 * app is in the background, where a composition may not run at all.
 */
@Composable
fun VaultAlbumsContent(
    component: VaultAlbumsComponent,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    val state by component.state.subscribeAsState()

    AlbumsScreen(
        state = state,
        imageLoader = imageLoader,
        onOpenAlbum = component::openAlbum,
        onOpenUnfiled = component::openUnfiled,
        onDismissError = component::dismissError,
        modifier = modifier,
    )
}
