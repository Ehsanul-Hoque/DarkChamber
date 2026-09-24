package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import coil3.ImageLoader
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The feature's one public composable: render a [VaultGalleryComponent].
 *
 * The host supplies the component and the session's [imageLoader] — the screen inside stays
 * internal so the app module cannot reach past this entry point into the feature's UI.
 *
 * The loader is passed in rather than built here because it holds decrypted previews in memory and
 * therefore belongs to the session, not to a composition: locking must be able to empty it even
 * while the app is in the background, where recomposition may not run at all.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun VaultGalleryContent(
    component: VaultGalleryComponent,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
) {
    val state by component.state.subscribeAsState()
    // Read once per composition: tile ages are coarse (minutes at finest), so recomputing "now" on
    // every recomposition would buy nothing and make the list re-render for no reason.
    val now = remember { Clock.System.now().toEpochMilliseconds() }

    GalleryScreen(
        state = state,
        now = now,
        imageLoader = imageLoader,
        onAddPhotos = component::importPhotos,
        onAddFiles = component::importFiles,
        onCancelImport = component::cancelImport,
        onAcknowledgeImport = component::acknowledgeImport,
        onRequestOriginalDelete = component::requestOriginalDelete,
        onConfirmOriginalDelete = component::confirmOriginalDelete,
        onKeepOriginals = component::keepOriginals,
        onDismissError = component::dismissError,
        modifier = modifier,
    )
}
