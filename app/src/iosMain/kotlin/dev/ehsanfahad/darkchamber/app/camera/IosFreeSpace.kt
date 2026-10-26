package dev.ehsanfahad.darkchamber.app.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.FreeSpace
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey

/**
 * Free space on the volume the vault lives on, read on the app's home directory — the vault root is
 * inside it, so it is the same volume.
 *
 * The capacity "for important usage": what the system will make room for when the user asks for
 * something, purging caches if it must. A recording is exactly that, so it is the honest figure; the
 * plain available capacity is lower and would refuse clips that would fit.
 *
 * `null` when the system cannot say, which refuses to record rather than guess. Unverified on a
 * device.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosFreeSpace : FreeSpace {
    override fun availableBytes(): Long? {
        val key = NSURLVolumeAvailableCapacityForImportantUsageKey ?: return null
        val values = NSURL.fileURLWithPath(NSHomeDirectory()).resourceValuesForKeys(listOf(key), error = null)
        return (values?.get(key) as? NSNumber)?.longLongValue
    }
}
