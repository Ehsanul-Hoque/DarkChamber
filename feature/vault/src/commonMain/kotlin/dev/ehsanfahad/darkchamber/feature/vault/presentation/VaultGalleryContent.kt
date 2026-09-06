package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The feature's one public composable: render a [VaultGalleryComponent].
 *
 * The host supplies nothing but the component — the screen inside stays internal so the app module
 * cannot reach past this entry point into the feature's UI.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun VaultGalleryContent(component: VaultGalleryComponent, modifier: Modifier = Modifier) {
    val state by component.state.subscribeAsState()
    // Read once per composition: tile ages are coarse (minutes at finest), so recomputing "now" on
    // every recomposition would buy nothing and make the list re-render for no reason.
    val now = remember { Clock.System.now().toEpochMilliseconds() }

    GalleryScreen(
        state = state,
        now = now,
        onImport = component::importFile,
        onDismissError = component::dismissError,
        modifier = modifier,
    )
}
