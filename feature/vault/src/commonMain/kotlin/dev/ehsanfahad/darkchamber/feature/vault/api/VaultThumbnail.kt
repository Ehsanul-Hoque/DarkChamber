package dev.ehsanfahad.darkchamber.feature.vault.api

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.ImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.feature.vault.presentation.GalleryFormat
import dev.ehsanfahad.darkchamber.feature.vault.presentation.VaultThumbnailKey
import dev.ehsanfahad.darkchamber.feature.vault.presentation.previewFailedIntegrity

/**
 * One vault item's encrypted preview, decrypted into memory and drawn — for a screen outside this
 * feature that has an item id and needs its picture, such as the camera's last-shot tile.
 *
 * It draws through [imageLoader], which must be **the session's one loader** (`vaultImageLoader`):
 * that loader has no disk cache, and its memory cache is the one the lock empties. Building another
 * for this would be a second cache of decrypted previews that nothing clears.
 *
 * Three looks, the gallery tile's own: the picture; nothing, while it decrypts or when the item has
 * no preview or one that will not decode; and a `danger` mark when the preview's bytes **failed their
 * integrity tag** — never a partial image, never a quiet fallback, and never retried. Unlike the
 * gallery's mark, this one is spoken, because no name is read out beside it to carry the meaning.
 *
 * Decorative otherwise: the caller describes what the picture is.
 */
@Composable
fun VaultThumbnail(itemId: ByteArray, imageLoader: ImageLoader, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        // Content-equal across instances, so a recomposition with the same id keeps the image.
        model = VaultThumbnailKey(itemId),
        imageLoader = imageLoader,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        loading = {},
        error = {
            // `.value`: the painter's state is a StateFlow — casting the flow itself compiles, always
            // yields null, and once silently turned the gallery's integrity mark into the ordinary
            // badge.
            val cause = (painter.state.value as? AsyncImagePainter.State.Error)?.result?.throwable
            if (previewFailedIntegrity(cause)) UnverifiedMark()
        },
        modifier = modifier,
    )
}

@Composable
private fun UnverifiedMark() {
    val description = GalleryFormat.unverifiedPreview()
    Box(
        contentAlignment = Alignment.Center,
        // Fills the slot and centres inside itself: Coil's subcompose slots stretch their content.
        modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
    ) {
        Text(text = "!", style = DarkChamberTheme.typography.label, color = DarkChamberTheme.colors.danger)
    }
}
