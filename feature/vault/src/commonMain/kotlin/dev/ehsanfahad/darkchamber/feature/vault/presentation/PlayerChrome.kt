package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerKind
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerState
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation

/**
 * What a player page shows around its picture, by kind. A video page and an audio page share the
 * controls; an audio page has no picture to clear the screen for, so its controls never hide, and no
 * picture to turn, so it has no orientation button.
 */
internal object PlayerChrome {
    /** Whether the bars and controls are on screen: always on an audio page, else as a tap left them. */
    fun barsVisible(kind: PlayerKind?, shownByTap: Boolean): Boolean = shownByTap || kind == PlayerKind.Audio

    /** The orientation button's target on this page, or `null` for no button. */
    fun orientationButton(kind: PlayerKind, target: ScreenOrientation?): ScreenOrientation? =
        if (kind == PlayerKind.Video) target else null

    /** Whether the controls hide by themselves after a while: only over a video that is playing. */
    fun autoHides(kind: PlayerKind?, state: PlayerState?): Boolean =
        kind == PlayerKind.Video && state != null && PlaybackRules.status(state) == PlaybackStatus.Playing
}
