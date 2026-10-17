package dev.ehsanfahad.darkchamber.core.designsystem

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView

/**
 * Marks this window's view root as unimportant for autofill and for content capture, descendants
 * included.
 *
 * `rootView` rather than the composition's own view, because the flag the platform reads is on the
 * root of the window: the dispatch that builds a structure starts there and, told to exclude
 * descendants, never reaches the Compose view below it. In an activity that root is the decor view;
 * in a dialog it is that dialog's own decor view, which is the case a host-side call misses.
 *
 * **What this stops, and what it does not.** The structure handed out no longer contains any
 * fillable field — measured on a device, the session's autofillable ids go from one id to none. The
 * session itself still starts, because Compose tells the platform a field was entered and nothing
 * outside Compose can prevent that; what the service receives is a window with nothing in it.
 *
 * In a [SideEffect] so it is reapplied after any recomposition that might have rebuilt the view, and
 * because it touches a platform object rather than composition state.
 *
 * Both properties are newer than this project's minimum, and the frameworks they belong to do not
 * exist below those versions either: autofill arrives in API 26, the content-capture flag in API 30.
 */
@Composable
actual fun ExcludeFromPlatformStructures() {
    val view = LocalView.current
    SideEffect {
        val root = view.rootView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            root.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
    }
}
