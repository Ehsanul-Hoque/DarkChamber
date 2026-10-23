package dev.ehsanfahad.darkchamber.feature.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.FreeSpace

/** Free space the test sets; `null` is a platform that cannot say. Counts reads. */
internal class FakeFreeSpace(var bytes: Long? = 64_000_000_000L) : FreeSpace {
    var reads = 0
        private set

    override fun availableBytes(): Long? {
        reads++
        return bytes
    }
}
