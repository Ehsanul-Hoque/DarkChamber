package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The 4 dp spacing grid (docs/DESIGN.md §6). */
@Immutable
data class DarkChamberSpacing(
    val spaceXxs: Dp = 4.dp,
    val spaceXs: Dp = 8.dp,
    val spaceSm: Dp = 12.dp,
    val spaceMd: Dp = 16.dp,
    val spaceLg: Dp = 24.dp,
    val spaceXl: Dp = 32.dp,
    val spaceXxl: Dp = 48.dp,
)
