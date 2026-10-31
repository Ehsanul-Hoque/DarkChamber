package dev.ehsanfahad.darkchamber.app.thumbnail

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import java.io.IOException

/**
 * One frame of a video, at most [maxEdgePx] on its longest edge, or `null` — no video track, a codec
 * the platform cannot decode, or any failure on the way.
 *
 * `MediaMetadataRetriever` wants random access, and [reader] gives it: each read the retriever makes
 * decrypts only the chunk it lands in, so an MP4 whose index (`moov`) is at the end — as phones write
 * it — costs a read at the end, not a read through the file to get there. The retriever is given the
 * bytes directly, never a path, so it has no file of its own to make.
 *
 * The frame comes back upright for a clip tagged to be shown turned: the size asked for is the
 * displayed one, and the platform applies the rotation (checked on Android 17). Below API 27 the
 * frame is decoded whole and scaled after; that path has not run on a device.
 *
 * [reader] belongs to the caller, which closes it; the retriever's own close of its data source does
 * not reach it.
 */
internal fun videoFrame(maxEdgePx: Int, reader: RangeReader): Bitmap? {
    val retriever = MediaMetadataRetriever()
    val source = ReaderDataSource(reader)
    return try {
        retriever.setDataSource(source)
        val at = previewFrameTimeUs(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
        val bounds = displayedSize(retriever) ?: return null
        val target = thumbnailTargetSize(bounds.width, bounds.height, maxEdgePx) ?: return null
        retriever.frameNear(at, target) { source.refused }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
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
//
// Not a second request once the source has refused a read: a refusal is final, so it would fail too,
// and a frame request whose input fails costs seconds — the platform waits on each decoder it tries
// (two, about 2 s each, on a Pixel 7 Pro), which is where a lock landing mid-preview spent 8 s.
private fun MediaMetadataRetriever.frameNear(atUs: Long, target: ThumbnailSize, refused: () -> Boolean): Bitmap? {
    for (option in listOf(MediaMetadataRetriever.OPTION_NEXT_SYNC, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) {
        if (refused()) return null
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

/**
 * The retriever's view of a [RangeReader]: its plaintext size, known from the moment it opened, and
 * reads that end in -1. A failed read — a chunk that fails its tag, a lock — is thrown as the
 * `IOException` this contract expects, so the retriever gives up on the file rather than being handed
 * a short read it would take as the end.
 */
private class ReaderDataSource(private val reader: RangeReader) : MediaDataSource() {
    /** Whether a read has failed. The reader refuses every read after its first refusal. */
    @Volatile
    var refused = false
        private set

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        when (val read = reader.readAt(position, buffer, offset, size)) {
            is Outcome.Ok -> if (read.value == RangeReader.END) -1 else read.value
            is Outcome.Err -> {
                refused = true
                throw IOException("the vault object could not be read")
            }
        }

    override fun getSize(): Long = reader.length

    // The reader is the caller's to close.
    override fun close() = Unit
}
