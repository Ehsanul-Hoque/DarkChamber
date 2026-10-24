package dev.ehsanfahad.darkchamber.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.ehsanfahad.darkchamber.app.holdPortrait

/** The hold is answered by the Swift host's app delegate; see [dev.ehsanfahad.darkchamber.app.holdsPortrait]. */
@Composable
internal actual fun HoldPortrait() {
    DisposableEffect(Unit) {
        holdPortrait(true)
        onDispose { holdPortrait(false) }
    }
}
