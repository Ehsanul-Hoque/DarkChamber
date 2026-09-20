package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.runtime.Composable

/**
 * Whether the OS has been asked to keep motion to a minimum.
 *
 * `docs/DESIGN.md` §8 makes this a rule rather than a nicety: **all motion respects the OS
 * reduced-motion setting — replace it with cross-fades.** Movement is the part that causes trouble
 * for people who set it; a fade still tells them something arrived.
 *
 * `expect`/`actual` rather than an injected seam, unlike the platform capabilities the app module
 * wires up: this reads a setting, needs no host object and no lifecycle, and a seam would mean
 * threading a dependency through every composable that animates.
 *
 * Read once per composition on both platforms. Someone who changes the setting mid-session sees it
 * take effect on the next screen rather than instantly, which is a fair trade for not subscribing
 * every animating composable to a system observer.
 */
@Composable
expect fun prefersReducedMotion(): Boolean
