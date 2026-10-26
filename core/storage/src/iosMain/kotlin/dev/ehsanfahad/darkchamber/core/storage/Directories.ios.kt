package dev.ehsanfahad.darkchamber.core.storage

import kotlinx.io.IOException
import kotlinx.io.files.Path

// kotlinx-io's native createDirectories already throws its mkdir's strerror, which the classifier
// reads, so a directory still missing afterwards has no cause left to report.
internal actual fun createMissingDirectory(dir: Path) {
    throw IOException("directory not created")
}
