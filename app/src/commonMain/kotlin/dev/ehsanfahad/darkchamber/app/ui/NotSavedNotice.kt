package dev.ehsanfahad.darkchamber.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.ehsanfahad.darkchamber.app.root.NotSavedSummary
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme

/**
 * The lines the not-saved notice draws now, or none. Never over the camera: it says its own
 * problems on its own message line, and a notice there would sit on the shutter row. Something lost
 * while a camera is open stays recorded and is shown once that camera closes.
 */
internal fun notSavedNoticeLines(cameraInFront: Boolean, summary: NotSavedSummary): List<String> =
    if (cameraInFront) emptyList() else notSavedLines(summary)

/**
 * What did not make it into the vault, on the first unlocked screen that can show it — straight
 * away when it happened with the vault open, after the next unlock when a lock caused it.
 *
 * It stays until dismissed rather than timing out: the news may be minutes of someone's recording,
 * and a notice that left on its own schedule could leave before it was read. A **polite live
 * region**, as the leave notice is, so a screen reader announces it when it appears.
 *
 * Drawn inside the app's own window, so the window's exclusions cover it; it opens no window of its
 * own.
 */
@Composable
internal fun NotSavedNotice(lines: List<String>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = DarkChamberTheme.spacing
    Surface(
        color = DarkChamberTheme.colors.surfaceRaised,
        shape = DarkChamberTheme.shapes.large,
        modifier = modifier
            .padding(spacing.spaceMd)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            modifier = Modifier.padding(start = spacing.spaceMd, end = spacing.spaceXs, top = spacing.spaceSm),
            verticalArrangement = Arrangement.spacedBy(spacing.spaceXxs),
        ) {
            for (line in lines) {
                Text(
                    line,
                    style = DarkChamberTheme.typography.body,
                    color = DarkChamberTheme.colors.textPrimary,
                    modifier = Modifier.padding(end = spacing.spaceSm),
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(DISMISS, style = DarkChamberTheme.typography.label, color = DarkChamberTheme.colors.accent)
            }
        }
    }
}

private const val DISMISS = "Dismiss"
