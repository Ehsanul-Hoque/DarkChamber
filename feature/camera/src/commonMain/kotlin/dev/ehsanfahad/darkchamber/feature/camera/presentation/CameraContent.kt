package dev.ehsanfahad.darkchamber.feature.camera.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraComponent

/**
 * The feature's one public composable: render a [CameraComponent].
 *
 * [lastShot] draws the newest capture's encrypted thumbnail for the tile, given its id. It is the
 * host's because the drawing belongs to the session's one image loader, whose memory cache the lock
 * already empties — a second loader built here would be a second cache of decrypted previews for the
 * lock to miss. The camera hands it an id and a size and nothing else.
 */
@Composable
fun CameraContent(
    component: CameraComponent,
    lastShot: @Composable (itemId: ByteArray, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by component.state.subscribeAsState()
    val tilt by component.tilt.collectAsState()

    CameraScreen(
        state = state,
        tilt = tilt,
        unreadableNameLabel = component.unreadableNameLabel,
        preview = { component.camera.Preview(it) },
        lastShot = lastShot,
        onShoot = component::shoot,
        onSetMode = component::setMode,
        onFlip = component::flipLens,
        onCycleFlash = component::cycleFlash,
        onToggleTorch = component::toggleTorch,
        onSwitchQuality = component::switchQuality,
        onToggleSound = component::toggleSound,
        onRequestMicrophone = component::requestMicrophone,
        onZoom = component::zoomTo,
        onFocus = component::focusAt,
        onAllow = component::requestPermission,
        onOpenSettings = component::openSettings,
        onClose = component::close,
        modifier = modifier,
    )
}
