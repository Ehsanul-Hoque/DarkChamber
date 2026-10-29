package dev.ehsanfahad.darkchamber.core.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
internal actual fun openPlatformFile(path: Path): PlatformFile {
    val fd = open(path.toString(), O_RDONLY)
    if (fd < 0) throw IOException(failed("open"))
    val size = memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) != 0) {
            val message = failed("fstat")
            close(fd)
            throw IOException(message)
        }
        st.st_size
    }
    return DescriptorFile(fd, size)
}

// pread reads at a position without moving the descriptor's offset.
@OptIn(ExperimentalForeignApi::class)
private class DescriptorFile(private val fd: Int, override val size: Long) : PlatformFile {
    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        val n = into.usePinned { pinned -> pread(fd, pinned.addressOf(offset), length.toULong(), position) }
        if (n < 0) throw IOException(failed("pread"))
        return n.toInt()
    }

    override fun close() {
        close(fd)
    }
}

// The message names the call and the platform's reason only — never the path (hard rule #4).
@OptIn(ExperimentalForeignApi::class)
private fun failed(call: String): String {
    val code = errno
    return "$call failed with errno $code (${strerror(code)?.toKString()})"
}
