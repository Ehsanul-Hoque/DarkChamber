package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Semantic color roles (docs/DESIGN.md §2). Consumers reference these roles only —
 * never a raw hex. The two factories below carry the exact token values for the
 * "chamber" (dark, default) and "print room" (light) themes.
 *
 * `scrim` folds the documented alpha into the color (dark `#000000 @ 60%`,
 * light `#1C1917 @ 40%`) so callers get a ready-to-use overlay.
 */
@Immutable
data class DarkChamberColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val sealed: Color,
    val sealedContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val scrim: Color,
    /** True for the dark ("chamber") theme — components key shadow-vs-surface-step off this (§5). */
    val isDark: Boolean,
)

/** Dark theme — "the chamber" (the app's native state, docs/DESIGN.md §2.1). */
fun darkChamberDarkColors(): DarkChamberColors = DarkChamberColors(
    background = Color(0xFF131110),
    surface = Color(0xFF1C1917),
    surfaceRaised = Color(0xFF26221F),
    outline = Color(0xFF3B3632),
    textPrimary = Color(0xFFEDE7DE),
    textSecondary = Color(0xFFA79E93),
    accent = Color(0xFFE3A144),
    onAccent = Color(0xFF201300),
    accentContainer = Color(0xFF3D2C0F),
    onAccentContainer = Color(0xFFF1C67E),
    sealed = Color(0xFF8FB896),
    sealedContainer = Color(0xFF1F2E23),
    danger = Color(0xFFE06A5E),
    onDanger = Color(0xFF2B0906),
    scrim = Color(0x99000000), // #000000 @ 60%
    isDark = true,
)

/** Light theme — "the print room" (docs/DESIGN.md §2.2). */
fun darkChamberLightColors(): DarkChamberColors = DarkChamberColors(
    background = Color(0xFFFAF9F7),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    outline = Color(0xFFE2DED8),
    textPrimary = Color(0xFF1C1917),
    textSecondary = Color(0xFF5F594F),
    accent = Color(0xFF7A5200),
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0xFFF4E3C2),
    onAccentContainer = Color(0xFF4A3200),
    sealed = Color(0xFF2E6B44),
    sealedContainer = Color(0xFFDCEBE0),
    danger = Color(0xFFB3372C),
    onDanger = Color(0xFFFFFFFF),
    scrim = Color(0x661C1917), // #1C1917 @ 40%
    isDark = false,
)
