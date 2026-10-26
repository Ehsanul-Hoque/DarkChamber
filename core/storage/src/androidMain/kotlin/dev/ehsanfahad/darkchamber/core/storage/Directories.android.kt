package dev.ehsanfahad.darkchamber.core.storage

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.io.IOException
import kotlinx.io.files.Path
import java.io.File

// Each missing level again through Os.mkdir, which reports why it failed; libcore's File.mkdirs()
// only says that it did. EEXIST is a level made meanwhile by another writer, which is what was wanted.
internal actual fun createMissingDirectory(dir: Path) {
    val missing = generateSequence(File(dir.toString())) { it.parentFile }.takeWhile { !it.exists() }.toList()
    for (level in missing.asReversed()) {
        try {
            Os.mkdir(level.path, OsConstants.S_IRWXU)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.EEXIST) throw IOException("directory not created", e)
        }
    }
}
