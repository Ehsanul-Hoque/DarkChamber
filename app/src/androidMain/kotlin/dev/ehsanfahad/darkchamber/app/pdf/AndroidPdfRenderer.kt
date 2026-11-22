package dev.ehsanfahad.darkchamber.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfOpened
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPageSize
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfBuffer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfDocument
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfRenderer
import java.io.FileDescriptor
import java.io.IOException
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readTo

/**
 * PDFs on Android, from memory only: the decrypted file goes into an anonymous memory file
 * (`memfd_create`) that no path names, and `PdfRenderer` reads it from there. Nothing is written to
 * storage — not `tmp/`, not a cache. Android 11 and later; before that the Java API has no memory-only
 * file, and this app does not write the plaintext to a file instead, so PDFs are [supported] = false.
 *
 * The memory is the kernel's: it is freed when the last descriptor to it closes — ours as soon as the
 * renderer has its own, the renderer's when the document closes — and **not wiped** (nothing here can
 * reach those pages to zero them). This code's own transfer buffer is zeroed after every write.
 */
internal class AndroidPdfRenderer(sdkInt: Int = Build.VERSION.SDK_INT) : VaultPdfRenderer {
    override val supported: Boolean = PdfMapping.supported(sdkInt)

    override fun newBuffer(sizeHint: Long): VaultPdfBuffer =
        if (supported && Build.VERSION.SDK_INT >= PdfMapping.FIRST_SDK) MemoryFileBuffer() else NoBuffer
}

/** Where PDFs cannot be shown: takes nothing in and refuses to open. The viewer asks [VaultPdfRenderer.supported] first. */
private object NoBuffer : VaultPdfBuffer {
    override val sink: RawSink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) = throw IOException("PDFs are not shown on this Android version")

        override fun flush() = Unit

        override fun close() = Unit
    }

    override fun open(): PdfOpened = PdfOpened.Refused(PdfRefusal.Unsupported)

    override fun close() = Unit
}

@RequiresApi(Build.VERSION_CODES.R)
private class MemoryFileBuffer : VaultPdfBuffer {
    // No MFD_CLOEXEC: Java's OsConstants does not name it, and O_CLOEXEC is not a memfd flag (EINVAL).
    private var own: FileDescriptor? = Os.memfd_create(NAME, 0)
    private val chunk = ByteArray(CHUNK_BYTES)

    override val sink: RawSink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            val fd = own ?: throw IOException("the buffer is closed")
            var left = byteCount
            try {
                while (left > 0) {
                    val count = minOf(left, CHUNK_BYTES.toLong()).toInt()
                    source.readTo(chunk, 0, count)
                    var written = 0
                    while (written < count) written += Os.write(fd, chunk, written, count - written)
                    left -= count
                }
            } catch (e: ErrnoException) {
                throw IOException("the memory buffer refused a write", e)
            } finally {
                chunk.fill(0)
            }
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    override fun open(): PdfOpened {
        val fd = own ?: return PdfOpened.Refused(PdfRefusal.Unreadable)
        var theirs: ParcelFileDescriptor? = null
        return try {
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            theirs = ParcelFileDescriptor.dup(fd)
            val renderer = PdfRenderer(theirs) // owns `theirs` from here, and closes it with itself
            theirs = null
            close() // ours: the renderer's own descriptor keeps the memory
            PdfOpened.Document(AndroidPdfDocument(renderer))
        } catch (e: Throwable) {
            // A refused file must not linger: the renderer took nothing, so close its copy too — and
            // ours, whatever went wrong, before deciding whether this was a refusal at all.
            runCatching { theirs?.close() }
            close()
            PdfOpened.Refused(PdfMapping.refusalFor(e) ?: throw e)
        }
    }

    override fun close() {
        val fd = own ?: return
        own = null
        chunk.fill(0)
        try {
            Os.close(fd)
        } catch (_: ErrnoException) {
            // Already gone; nothing more to release.
        }
    }

    private companion object {
        /** What the descriptor is called in the process's table: no item's name ever goes here. */
        const val NAME = "viewer-pdf"
        const val CHUNK_BYTES = 64 * 1024
    }
}

/**
 * One open PDF. Every method holds the document's lock, so a [close] waits for a [render] under way —
 * `PdfRenderer` opens one page at a time and is not safe from two threads; the viewer also makes its
 * calls one at a time, so the lock is a second guard, not the first.
 */
private class AndroidPdfDocument(private val renderer: PdfRenderer) : VaultPdfDocument {
    private var closed = false

    override val pageCount: Int = renderer.pageCount

    @Synchronized
    override fun pageSize(index: Int): PdfPageSize =
        try {
            renderer.openPage(index).use { PdfPageSize(it.width, it.height) }
        } catch (_: Exception) {
            PdfPageSize(0, 0)
        }

    @Synchronized
    override fun render(index: Int, widthPx: Int): ImageBitmap? {
        if (closed || widthPx <= 0) return null
        return try {
            renderer.openPage(index).use { page ->
                if (page.width <= 0) return null
                val heightPx = (widthPx.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE) // a page with no background of its own is paper, not black
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        renderer.close()
    }
}
