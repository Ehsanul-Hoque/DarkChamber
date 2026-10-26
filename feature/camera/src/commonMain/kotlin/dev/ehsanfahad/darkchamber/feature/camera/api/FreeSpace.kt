package dev.ehsanfahad.darkchamber.feature.camera.api

/**
 * How much space is free on the volume the vault lives on — the platform seam a recording is sized
 * from. The app implements it over the vault root: `StatFs` on Android, the volume's
 * important-usage capacity on iOS.
 */
fun interface FreeSpace {
    /** Bytes free right now, or `null` when the platform cannot say. Never throws. */
    fun availableBytes(): Long?
}
