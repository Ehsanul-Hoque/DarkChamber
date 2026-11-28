package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.runtime.Immutable

/**
 * Motion durations in milliseconds (docs/DESIGN.md §8). Motion explains state, never
 * decorates. Lock is never animated slower than [motionBase] — the crypto teardown
 * runs first and the iris only catches up; the animation may never delay it.
 */
@Immutable
data class DarkChamberMotion(
    val motionFast: Int = 120,
    val motionBase: Int = 200,
    val motionSlow: Int = 320,
)
