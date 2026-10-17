package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.runtime.Composable

/**
 * Nothing to do on iOS, and the reason is worth stating rather than leaving as an empty body.
 *
 * iOS has no equivalent of the structures this guards against: there is no assist session that
 * flattens a window's text for another process, and no autofill service that receives a view tree.
 * Its AutoFill works through the keyboard against a field's own content type, which this project
 * never sets — and `docs/THREAT_MODEL.md` already records that iOS cannot block screen capture at
 * all, which is the larger gap on that platform.
 *
 * Left as an empty actual rather than making the common declaration Android-only, so that the rule
 * stays "every window that shows vault content calls this" on both platforms. A later iOS exposure
 * of this kind gets closed here, in the place that is already called from everywhere it matters.
 */
@Composable
actual fun ExcludeFromPlatformStructures() {
    // No platform structure to opt out of; see the note above.
}
