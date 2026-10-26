package dev.ehsanfahad.darkchamber.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * The activity this composition is drawn in, not the one the app's holder publishes: the holder is
 * set on resume and cleared on pause, and a composition's first effects can run on either side of
 * that.
 *
 * Released to `UNSPECIFIED`, the manifest's own setting, rather than to whatever was there before:
 * nothing else in the app requests an orientation, and after the activity is recreated "what was
 * there before" is this hold's own portrait.
 *
 * **Not released while the activity is being recreated.** The request lives on the activity's
 * record, which outlives the instance across a configuration change. Taking a held portrait in
 * landscape recreates the activity once; releasing it on the way out would turn it back to
 * landscape, which recreates it again, and so on.
 */
@Composable
internal actual fun HoldPortrait() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (!activity.isChangingConfigurations) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
