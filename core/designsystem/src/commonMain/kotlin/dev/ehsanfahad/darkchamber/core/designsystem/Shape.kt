package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner radii (docs/DESIGN.md §4). `radiusFull` is the full-circle radius and is
 * RESERVED for the aperture family (§7) — its rarity is what makes it read as the
 * brand, so only [Aperture] and other aperture elements may use it.
 */
@Immutable
data class DarkChamberShapes(
    val radiusSm: Dp = 6.dp,
    val radiusMd: Dp = 10.dp,
    val radiusLg: Dp = 14.dp,
    val radiusXl: Dp = 20.dp,
) {
    val small: RoundedCornerShape = RoundedCornerShape(radiusSm)
    val medium: RoundedCornerShape = RoundedCornerShape(radiusMd)
    val large: RoundedCornerShape = RoundedCornerShape(radiusLg)
    val extraLarge: RoundedCornerShape = RoundedCornerShape(radiusXl)
}
