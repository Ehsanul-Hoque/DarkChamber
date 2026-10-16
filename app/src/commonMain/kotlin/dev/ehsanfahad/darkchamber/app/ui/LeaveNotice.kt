package dev.ehsanfahad.darkchamber.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme

/**
 * What the vault's grid says when a back press is swallowed rather than obeyed.
 *
 * **Both halves of it are load-bearing.** The first says what the next press does, because a press
 * that did nothing visible is otherwise indistinguishable from a press the app missed — and the user
 * will try again, which is the thing that must not be a surprise. The second says what leaving costs,
 * which is the whole reason the press is being swallowed: leaving locks the vault, so what is on
 * screen is gone and unlocking is how it comes back.
 *
 * Nothing beyond those two (hard rule 6). In particular nothing about the session surviving — it does
 * not: leaving zeroizes the key and purges the decrypted scratch like any other lock.
 *
 * "Go back", not "press back": the same thing happens for a press and for an edge gesture, and on a
 * phone with gesture navigation there is no button to press.
 */
internal fun leaveNoticeText(): String = "Go back again to leave. Leaving locks the vault."

/**
 * The notice itself, drawn only while the offer stands.
 *
 * A plain raised surface rather than a snackbar host: there is one message, it has no action and no
 * timer of its own — it is on screen exactly while the component says the offer is open, so the
 * thing the user sees cannot outlive the thing the next press will do.
 *
 * A **polite live region**, so a screen reader announces it when it appears. Without that the
 * swallowed press is silent to someone who cannot see the notice, which is the one audience for whom
 * "nothing happened" is hardest to tell from "it is waiting for a second press".
 */
@Composable
internal fun LeaveNotice(modifier: Modifier = Modifier) {
    Surface(
        color = DarkChamberTheme.colors.surfaceRaised,
        shape = DarkChamberTheme.shapes.large,
        modifier = modifier
            .padding(DarkChamberTheme.spacing.spaceMd)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            leaveNoticeText(),
            style = DarkChamberTheme.typography.body,
            color = DarkChamberTheme.colors.textPrimary,
            modifier = Modifier.padding(
                horizontal = DarkChamberTheme.spacing.spaceMd,
                vertical = DarkChamberTheme.spacing.spaceSm,
            ),
        )
    }
}
