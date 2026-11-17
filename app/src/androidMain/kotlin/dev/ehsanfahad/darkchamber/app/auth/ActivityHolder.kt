package dev.ehsanfahad.darkchamber.app.auth

import androidx.fragment.app.FragmentActivity
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the current `FragmentActivity` so the biometric authenticator can present a `BiometricPrompt`
 * without a global/static Activity reference (which would leak). `MainActivity` sets it on
 * resume and clears it on pause; the authenticator reads it only at prompt time. Single-Activity app.
 */
interface ActivityHolder {
    var activity: FragmentActivity?

    /**
     * Calls [action] with each activity as it comes to the front — a new one after a rotation, or the
     * same one back from a picker. For what lives on a window and must be put on the new one.
     */
    fun whenShown(action: (FragmentActivity) -> Unit) = Unit
}

class DefaultActivityHolder : ActivityHolder {
    private val shown = CopyOnWriteArrayList<(FragmentActivity) -> Unit>()

    override var activity: FragmentActivity? = null
        set(value) {
            field = value
            if (value != null) shown.forEach { it(value) }
        }

    override fun whenShown(action: (FragmentActivity) -> Unit) {
        shown += action
    }
}
