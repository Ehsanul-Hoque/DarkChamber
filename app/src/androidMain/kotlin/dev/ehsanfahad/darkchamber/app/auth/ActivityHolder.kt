package dev.ehsanfahad.darkchamber.app.auth

import androidx.fragment.app.FragmentActivity

/**
 * Holds the current `FragmentActivity` so the biometric authenticator can present a `BiometricPrompt`
 * without a global/static Activity reference (which would leak). `MainActivity` (TASK-011) sets it on
 * resume and clears it on pause; the authenticator reads it only at prompt time. Single-Activity app.
 */
interface ActivityHolder {
    var activity: FragmentActivity?
}

class DefaultActivityHolder : ActivityHolder {
    override var activity: FragmentActivity? = null
}
