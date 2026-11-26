package dev.ehsanfahad.darkchamber.feature.vault.api

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.io.RawSink

/**
 * The platform seam that shows a PDF for the viewer from memory. The app module implements it per
 * platform — `PdfRenderer` over a memory-only file on Android 11+, PDFKit on iOS — as it does the image
 * decoder and the players.
 *
 * The contract every implementation keeps:
 *
 * - **Memory only.** Nothing the buffer receives or the document draws is written to a file, `tmp/`
 *   included, or to a cache (hard rule #1). On Android the buffer is an anonymous memory file that no
 *   path names.
 * - **Filled, then opened — never opened early.** The viewer streams the decrypted item into
 *   [VaultPdfBuffer.sink] and calls [VaultPdfBuffer.open] only once the vault has verified the whole
 *   file. Nothing is drawn from a buffer that was not opened.
 * - **Refusals by type**, never by the words of a message: see [PdfRefusal].
 * - **Pictures only.** A page is drawn as a picture: no links are followed, no text is extracted.
 * - **One call at a time.** Neither the buffer nor the document is safe to use from two threads at
 *   once (Android's renderer opens one page at a time); the viewer makes every call on one dispatcher.
 * - **Declines, never throws**, across this seam.
 */
interface VaultPdfRenderer {
    /** Whether this platform can show PDFs at all. Asked before anything is read or decrypted. */
    val supported: Boolean

    /**
     * An empty buffer for one PDF. [sizeHint] is the item's plaintext size from the size column, for a
     * platform that sizes its buffer up front. The caller closes it unless [VaultPdfBuffer.open]
     * returned a document, which owns it from then on.
     */
    fun newBuffer(sizeHint: Long): VaultPdfBuffer
}

/** Memory to hold one decrypted PDF while it is shown. */
interface VaultPdfBuffer {
    /** Where the decrypt writes, from the first byte to the last. The caller does not close it. */
    val sink: RawSink

    /**
     * The document, once the buffer holds the whole verified file — or why the platform will not show
     * it. A [PdfOpened.Refused] has already released the buffer. Called at most once.
     */
    fun open(): PdfOpened

    /** Releases the buffer and everything in it. Safe to call more than once, and after [open]. */
    fun close()
}

/** What opening a filled buffer gave. */
sealed interface PdfOpened {
    /** The document, which now owns the buffer: closing it releases both. */
    data class Document(
        val document: VaultPdfDocument,
    ) : PdfOpened

    data class Refused(
        val reason: PdfRefusal,
    ) : PdfOpened
}

/** One open PDF. Closing it releases its buffer. */
interface VaultPdfDocument {
    val pageCount: Int

    /** Page [index]'s size, in points (1/72 inch), from which its shape on screen is taken. */
    fun pageSize(index: Int): PdfPageSize

    /**
     * Page [index] drawn [widthPx] pixels wide, keeping its shape, or `null` if this page could not be
     * drawn — the others may still be. Can take a while on a heavy page; never call it on the main thread.
     */
    fun render(
        index: Int,
        widthPx: Int,
    ): ImageBitmap?

    /**
     * Releases the document, its renderer and its buffer. Safe to call more than once. Waits for a
     * render in progress, which cannot be interrupted, to finish.
     */
    fun close()
}

/** A page's size in points. */
data class PdfPageSize(
    val widthPt: Int,
    val heightPt: Int,
)

/** Where a reader is in a PDF: a page (from 0) and how far down it, in pixels as last laid out. */
data class PdfPlace(
    val page: Int = 0,
    val offsetPx: Int = 0,
)

/**
 * Why the platform will not show a PDF that read and verified. Told apart by what the platform reports
 * — its exception type or its document state — never by the words of a message, which change from one
 * platform version to the next.
 */
enum class PdfRefusal {
    /** The document is locked with a password. */
    NeedsPassword,

    /** The renderer will not read it: malformed, or using something it refuses. */
    Unreadable,

    /** This platform cannot show PDFs at all (Android before 11). */
    Unsupported,
}
