package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The DarkChamber theme (docs/DESIGN.md). Wraps a subtree so feature UI reads
 * color/type/shape/motion/spacing **roles** via the [DarkChamberTheme] accessor —
 * never a raw hex, never a hardcoded dp. Mirrors MaterialTheme's shape: a composable
 * that installs the tokens, plus an object exposing them at any depth.
 *
 * Dark ("the chamber") is the app's native state, so [darkTheme] defaults to true;
 * a host may pass the system setting to follow light/dark.
 */
@Composable
fun DarkChamberTheme(
    darkTheme: Boolean = true,
    colors: DarkChamberColors = if (darkTheme) darkChamberDarkColors() else darkChamberLightColors(),
    typography: DarkChamberTypography = DarkChamberTypography(),
    shapes: DarkChamberShapes = DarkChamberShapes(),
    spacing: DarkChamberSpacing = DarkChamberSpacing(),
    motion: DarkChamberMotion = DarkChamberMotion(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalDarkChamberColors provides colors,
        LocalDarkChamberTypography provides typography,
        LocalDarkChamberShapes provides shapes,
        LocalDarkChamberSpacing provides spacing,
        LocalDarkChamberMotion provides motion,
        content = content,
    )
}

/** Role accessor — `DarkChamberTheme.colors.accent`, `DarkChamberTheme.spacing.spaceMd`, … */
object DarkChamberTheme {
    val colors: DarkChamberColors
        @Composable @ReadOnlyComposable get() = LocalDarkChamberColors.current
    val typography: DarkChamberTypography
        @Composable @ReadOnlyComposable get() = LocalDarkChamberTypography.current
    val shapes: DarkChamberShapes
        @Composable @ReadOnlyComposable get() = LocalDarkChamberShapes.current
    val spacing: DarkChamberSpacing
        @Composable @ReadOnlyComposable get() = LocalDarkChamberSpacing.current
    val motion: DarkChamberMotion
        @Composable @ReadOnlyComposable get() = LocalDarkChamberMotion.current
}

// staticCompositionLocalOf: the tokens rarely change, so reads don't need per-value
// recomposition tracking. Defaults are the dark theme (the app's native state).
internal val LocalDarkChamberColors = staticCompositionLocalOf { darkChamberDarkColors() }
internal val LocalDarkChamberTypography = staticCompositionLocalOf { DarkChamberTypography() }
internal val LocalDarkChamberShapes = staticCompositionLocalOf { DarkChamberShapes() }
internal val LocalDarkChamberSpacing = staticCompositionLocalOf { DarkChamberSpacing() }
internal val LocalDarkChamberMotion = staticCompositionLocalOf { DarkChamberMotion() }
