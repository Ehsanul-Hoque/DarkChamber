package dev.ehsanfahad.darkchamber.core.designsystem

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android reports this as an animation **scale**, and zero is how "Remove animations" (and the
 * developer-options equivalent) expresses itself. `ANIMATOR_DURATION_SCALE` is the one that governs
 * in-app property animations, which is what everything here is.
 */
@Composable
actual fun prefersReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
