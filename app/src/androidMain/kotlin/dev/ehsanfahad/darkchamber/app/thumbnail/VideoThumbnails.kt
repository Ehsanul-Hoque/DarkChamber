package dev.ehsanfahad.darkchamber.app.thumbnail

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.io.RawSource

/**
 * One frame of a video, at most [maxEdgePx] on its longest edge, or `null` — no video track, a codec
 * the platform cannot decode, or any failure on the way.
 *
 * `MediaMetadataRetriever` wants random access and the vault hands over a stream, so it reads through
 * a [SeekingReader]: forward jumps skip, backward ones re-open, and the blocks read last are kept. An
 * MP4 whose index (`moov`) is at the end — every phone's recorder writes it there — costs one read
 * through the file to reach it: about 1.4 s for a 1.1 GB clip on a Pixel 7 Pro, against a quarter of
 * a second with the index at the start. The retriever is given the bytes directly, never a path, so
 * it has no file of its own to make.
 *
 * The frame comes back upright for a clip tagged to be shown turned: the size asked for is the
 * displayed one, and the platform applies the rotation (checked on Android 17). Below API 27 the
 * frame is decoded whole and scaled after; that path has not run on a device.
 */
internal fun videoFrame(maxEdgePx: Int, openSource: () -> RawSource): Bitmap? {
    val reader = SeekingReader(openSource = openSource)
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(ReaderDataSource(reader))
        val at = previewFrameTimeUs(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
        val bounds = displayedSize(retriever) ?: return null
        val target = thumbnailTargetSize(bounds.width, bounds.height, maxEdgePx) ?: return null
        retriever.frameNear(at, target)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
        reader.close()
    }
}

// The size the video is shown at: its coded size, turned when its rotation is a quarter turn.
private fun displayedSize(retriever: MediaMetadataRetriever): ThumbnailSize? {
    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    val coded = ThumbnailSize(width, height)
    return if (rotation % 180 != 0) coded.swapped() else coded
}

// The first key frame at or after [atUs] — decoding no further than it — or, where there is none
// after it, the nearest one. Scaled by the platform while decoding where it can (API 27+).
private fun MediaMetadataRetriever.frameNear(atUs: Long, target: ThumbnailSize): Bitmap? {
    for (option in listOf(MediaMetadataRetriever.OPTION_NEXT_SYNC, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) {
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            getScaledFrameAtTime(atUs, option, target.width, target.height)
        } else {
            getFrameAtTime(atUs, option)?.let { full ->
                Bitmap.createScaledBitmap(full, target.width, target.height, true).also { if (it !== full) full.recycle() }
            }
        }
        if (frame != null) return frame
    }
    return null
}

/** The retriever's view of a [SeekingReader]. The size is not known up front; -1 says so. */
private class ReaderDataSource(private val reader: SeekingReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.readAt(position, buffer, offset, size)

    override fun getSize(): Long = -1

    override fun close() = reader.close()
}
