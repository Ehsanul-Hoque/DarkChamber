package dev.ehsanfahad.darkchamber.app.player

import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation

/**
 * What the player holds the window to, remembered so a **new window** can be told it too. A window's
 * brightness and its keep-on flag belong to that window: when the orientation button recreates the
 * activity, or the app comes back from a picker, the window in front is a fresh one at the system's
 * defaults, and the viewer — which already sent its commands — does not send them again.
 */
internal class HeldDisplay {
    /** The slide's level, or `null` while the system's own is in force. */
    var brightness: Float? = null

    var keepOn: Boolean = false

    var orientation: ScreenOrientation = ScreenOrientation.AppDefault

    /**
     * What a new window has to be told: only what is held. A fresh window is already at the system's
     * defaults, and telling it "the app's own orientation" would undo a hold someone else set on it.
     */
    fun forNewWindow(): NewWindow =
        NewWindow(brightness, keepOn, orientation.takeIf { it != ScreenOrientation.AppDefault })
}

/**
 * The window a change goes to: the one in front, or else the one last in front while it still lives.
 * A lock lands in `onStop`, after `onPause` has cleared the one in front, and what it puts back has to
 * reach the window that comes back — not wait for a window that will never be told.
 */
internal fun <T : Any> windowToChange(
    inFront: T?,
    lastShown: T?,
    gone: (T) -> Boolean,
): T? = inFront ?: lastShown?.takeUnless(gone)

/** What a new window is told; `null` or `false` leaves that part of it alone. */
internal data class NewWindow(
    val brightness: Float?,
    val keepOn: Boolean,
    val orientation: ScreenOrientation?,
)
