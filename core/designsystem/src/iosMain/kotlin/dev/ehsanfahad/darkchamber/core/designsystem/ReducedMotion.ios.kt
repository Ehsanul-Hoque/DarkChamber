package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/** iOS asks the question directly: Settings → Accessibility → Motion → Reduce Motion. */
@Composable
actual fun prefersReducedMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }
