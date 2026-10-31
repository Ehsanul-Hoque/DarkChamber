package dev.ehsanfahad.darkchamber.core.storage

import kotlinx.io.files.Path
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

internal actual fun openPlatformFile(path: Path): PlatformFile {
    val channel = RandomAccessFile(path.toString(), "r").channel
    // The size is read in the constructor; if that throws, nothing else would close the file.
    return try {
        ChannelFile(channel)
    } catch (e: Throwable) {
        channel.close()
        throw e
    }
}

// FileChannel.read(buffer, position) reads at a position without moving any shared cursor.
private class ChannelFile(private val channel: FileChannel) : PlatformFile {
    override val size: Long = channel.size()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(into, offset, length), position)

    override fun close() = channel.close()
}
