package dev.ehsanfahad.darkchamber.feature.vault.api

/**
 * The display controls a player page needs from the platform: the screen's brightness, a forced
 * orientation, and keeping the screen awake while something plays. The app implements it per platform. Volume is not here — it belongs to each player
 * ([VaultPlayer.setVolume]).
 *
 * The viewer decides when; this only does. It changes nothing on its own, and everything it changes
 * the viewer puts back: brightness when a video page is left, and all of it when the viewer closes or
 * the vault locks (iOS's brightness is the whole phone's, so putting it back matters most there).
 */
interface PlayerDisplay {
    /** The brightness now, from 0 to 1 — the one last set here, or the system's own. */
    fun brightness(): Float

    /** Sets the brightness to [level], from 0 to 1; `null` hands it back to the system. */
    fun setBrightness(level: Float?)

    /** Holds the window in [orientation], whatever the phone's own auto-rotate is set to. */
    fun setOrientation(orientation: ScreenOrientation)

    /** Keeps the screen from sleeping while [on]; `false` lets it sleep on the phone's own timer again. */
    fun keepScreenOn(on: Boolean)
}

enum class ScreenOrientation {
    /** What the app does without the viewer: follow the phone. */
    AppDefault,
    Portrait,
    Landscape,
}
