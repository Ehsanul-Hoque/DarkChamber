package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.runtime.Composable

/**
 * Whether a screen reader is running — TalkBack (touch exploration) on Android, VoiceOver on iOS.
 *
 * For anything that would otherwise vanish on a timer: someone moving through a screen by touch takes
 * longer than a sighted tap, and a control that hides under them strands them. `expect`/`actual` for
 * the reason [prefersReducedMotion] is.
 */
@Composable
expect fun screenReaderOn(): Boolean
