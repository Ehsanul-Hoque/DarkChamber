package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsVoiceOverRunning

/** VoiceOver. Read once per composition, as reduced motion is; a change shows on the next screen. */
@Composable
actual fun screenReaderOn(): Boolean = remember { UIAccessibilityIsVoiceOverRunning() }
