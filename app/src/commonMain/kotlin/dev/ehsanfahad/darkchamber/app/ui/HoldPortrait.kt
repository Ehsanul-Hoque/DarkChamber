package dev.ehsanfahad.darkchamber.app.ui

import androidx.compose.runtime.Composable

/**
 * Holds the window in portrait for as long as this is in the composition, and lets it turn freely
 * again when it leaves.
 *
 * Called by the camera screen only. The camera does not rotate: it keeps one layout and turns its
 * icons with the device instead, so a photo's viewfinder and its controls never jump while the user
 * is framing. Every other screen rotates as before.
 *
 * The platform can refuse: Android ignores the request on large screens and in multi-window, and
 * iOS in iPad multitasking. The camera's wide layout covers those windows, so a refusal is not a
 * broken screen.
 */
@Composable
internal expect fun HoldPortrait()
