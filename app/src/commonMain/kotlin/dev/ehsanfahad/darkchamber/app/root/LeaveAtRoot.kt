package dev.ehsanfahad.darkchamber.app.root

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether a back press at the vault's own grid should leave the app.
 *
 * @param armedFor how long ago the previous press offered to leave, or `null` when no press is
 *   waiting for a second one. Measured from a **monotonic** source: a wall clock that jumped
 *   backwards would make a long-expired offer look fresh, and one that jumped forward would expire
 *   an offer the user is halfway through answering.
 * @param window how long that offer stands.
 *
 * A function of two values rather than a flag on the component, because the two halves of this
 * behaviour have different reliability needs. Taking the notice *off* the screen is cosmetic and runs
 * on a timer; deciding whether a press ends the session is not, and must not depend on that timer
 * having fired. So the decision re-measures the elapsed time every time, and a notice still on screen
 * after the window has passed buys nothing — the press arms a new offer instead of leaving.
 *
 * The edge is inside the offer: a press at exactly the window leaves. The alternative is a press the
 * user made within the time they were given being silently treated as a first press.
 */
internal fun backPressLeaves(armedFor: Duration?, window: Duration): Boolean =
    armedFor != null && armedFor >= Duration.ZERO && armedFor <= window

/**
 * How long "go back again to leave" stands.
 *
 * Two seconds, which is the convention this behaviour has on Android and — more to the point — about
 * as long as the notice is worth leaving on screen. Shorter turns the second press into a race with
 * a user who is reading; longer leaves the offer open after the intent behind the first press has
 * passed, so a stray press minutes later can still be the one that ends the session.
 */
internal val LEAVE_WINDOW: Duration = 2.seconds
