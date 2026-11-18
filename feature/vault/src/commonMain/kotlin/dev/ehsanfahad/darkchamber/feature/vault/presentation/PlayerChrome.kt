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

    /**
     * How far up, in pixels, to move the audio page's badge from the middle of the page so that its
     * bottom sits [gap] above the centre control ([centreSize] square, also centred) — never under it.
     */
    fun badgeShiftAboveCentre(badgeHeight: Int, centreSize: Int, gap: Int): Int =
        -(centreSize / 2) - gap - (badgeHeight + 1) / 2

    /**
     * Whether the page is short enough — a phone on its side — that the player's bottom bar must be one
     * row of controls under the seek bar, Details and Delete included, to stay clear of the centre
     * control. Material's compact height: under 480 dp.
     */
    fun compactBar(heightDp: Float): Boolean = heightDp < COMPACT_HEIGHT_DP

    private const val COMPACT_HEIGHT_DP = 480f

    /**
     * What a screen reader can do from the page itself, beyond its buttons: a screen reader takes every
     * touch for itself, so the page's own gestures never reach it. Showing and hiding the controls
     * belongs to a video page (an audio page's never hide); skipping ten seconds needs a player.
     */
    fun readerActions(kind: PlayerKind, hasPlayer: Boolean): List<ReaderAction> = buildList {
        if (kind == PlayerKind.Video) add(ReaderAction.ToggleControls)
        if (hasPlayer) {
            add(ReaderAction.Back)
            add(ReaderAction.Forward)
        }
    }

    /**
     * Whether the controls hide by themselves after a while: only over a video that is playing, and
     * never while a [screenReader] runs — someone moving through the controls by touch would have
     * them vanish under them.
     */
    fun autoHides(kind: PlayerKind?, state: PlayerState?, screenReader: Boolean = false): Boolean =
        !screenReader && kind == PlayerKind.Video && state != null &&
            PlaybackRules.status(state) == PlaybackStatus.Playing
}

/** A screen-reader action on a player page. */
internal enum class ReaderAction { ToggleControls, Back, Forward }
