package dev.ehsanfahad.darkchamber.app.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import cnames.structs.CGPDFDocument
import cnames.structs.__CFData
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfOpened
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPageSize
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfBuffer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfDocument
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfRenderer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.readTo
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawPDFPage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGPDFDocumentCreateWithProvider
import platform.CoreGraphics.CGPDFDocumentGetNumberOfPages
import platform.CoreGraphics.CGPDFDocumentGetPage
import platform.CoreGraphics.CGPDFDocumentIsUnlocked
import platform.CoreGraphics.CGPDFDocumentRelease
import platform.CoreGraphics.CGPDFPageGetBoxRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGPDFMediaBox
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMutableData
import platform.Foundation.appendBytes
import platform.Foundation.resetBytesInRange

/**
 * PDFs on iOS, from memory only: the decrypted file is gathered into an `NSMutableData`, and
 * CoreGraphics — the engine under PDFKit — reads it from there. Nothing is written to a file, `tmp/`
 * included, or to a cache, and pages are drawn as pictures: nothing interactive (no links, no text)
 * exists here.
 *
 * **Our copy is wiped.** When the buffer is released — refused, closed, or with its document — its
 * bytes are zeroed and its length set to nothing. CoreGraphics' own parse of the file is its own and is
 * freed, not wiped, when the document is released.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPdfRenderer : VaultPdfRenderer {
    override val supported: Boolean = true

    override fun newBuffer(sizeHint: Long): VaultPdfBuffer = IosPdfBuffer()
}

@OptIn(ExperimentalForeignApi::class)
internal class IosPdfBuffer : VaultPdfBuffer {
    // Grows as the file is appended; the size hint is not needed to size it.
    private var data: NSMutableData? = NSMutableData()
    private val chunk = ByteArray(CHUNK_BYTES)

    /** Bytes of the file this buffer holds now. */
    internal val held: Long get() = data?.length?.toLong() ?: 0L

    override val sink: RawSink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            val into = data ?: throw IOException("the buffer is closed")
            var left = byteCount
            try {
                while (left > 0) {
                    val count = minOf(left, CHUNK_BYTES.toLong()).toInt()
                    source.readTo(chunk, 0, count)
                    chunk.usePinned { into.appendBytes(it.addressOf(0), count.toULong()) }
                    left -= count
                }
            } finally {
                chunk.fill(0)
            }
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    override fun open(): PdfOpened {
        val bytes = data ?: return PdfOpened.Refused(PdfRefusal.Unreadable)
        val document = openDocument(bytes)
        if (document == null) {
            close()
            return PdfOpened.Refused(PdfRefusal.Unreadable)
        }
        val refusal = when {
            !CGPDFDocumentIsUnlocked(document) -> PdfRefusal.NeedsPassword
            CGPDFDocumentGetNumberOfPages(document) == 0uL -> PdfRefusal.Unreadable
            else -> null
        }
        if (refusal != null) {
            CGPDFDocumentRelease(document)
            close()
            return PdfOpened.Refused(refusal)
        }
        return PdfOpened.Document(IosPdfDocument(document, this))
    }

    /** CoreGraphics' document over [bytes], or `null` where it will not read them as a PDF at all. */
    private fun openDocument(bytes: NSMutableData): CPointer<CGPDFDocument>? {
        val cfData = CFBridgingRetain(bytes)?.reinterpret<__CFData>() ?: return null
        try {
            val provider = CGDataProviderCreateWithCFData(cfData) ?: return null
            try {
                return CGPDFDocumentCreateWithProvider(provider)
            } finally {
                CGDataProviderRelease(provider) // the document keeps its own hold on the provider
            }
        } finally {
            CFRelease(cfData)
        }
    }

    override fun close() {
        val bytes = data ?: return
        data = null
        bytes.resetBytesInRange(NSMakeRange(0u, bytes.length)) // zeroed, then let go
        bytes.setLength(0u)
        chunk.fill(0)
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}

/**
 * One open PDF. Not safe from two threads at once — the viewer makes its calls one at a time — and
 * closing releases CoreGraphics' document before the buffer under it is wiped.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosPdfDocument(
    private var document: CPointer<CGPDFDocument>?,
    private val buffer: IosPdfBuffer,
) : VaultPdfDocument {
    override val pageCount: Int = document?.let { CGPDFDocumentGetNumberOfPages(it).toInt() } ?: 0

    override fun pageSize(index: Int): PdfPageSize {
        val page = document?.let { CGPDFDocumentGetPage(it, (index + 1).toULong()) } ?: return PdfPageSize(0, 0)
        return CGPDFPageGetBoxRect(page, kCGPDFMediaBox).useContents {
            PdfPageSize(size.width.toInt(), size.height.toInt())
        }
    }

    override fun render(index: Int, widthPx: Int): ImageBitmap? {
        val open = document ?: return null
        if (widthPx <= 0) return null
        val page = CGPDFDocumentGetPage(open, (index + 1).toULong()) ?: return null
        val box = CGPDFPageGetBoxRect(page, kCGPDFMediaBox)
        val (x, y, pageWidth, pageHeight) = box.useContents { listOf(origin.x, origin.y, size.width, size.height) }
        if (pageWidth <= 0.0 || pageHeight <= 0.0) return null
        val heightPx = (widthPx.toLong() * pageHeight.toLong() / pageWidth.toLong()).toInt().coerceAtLeast(1)

        val bitmap = Bitmap()
        if (!bitmap.allocPixels(ImageInfo(widthPx, heightPx, ColorType.RGBA_8888, ColorAlphaType.PREMUL))) {
            bitmap.close()
            return null
        }
        val pixels = bitmap.peekPixels() ?: run {
            bitmap.close()
            return null
        }
        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(
            data = interpretCPointer<ByteVar>(pixels.addr),
            width = widthPx.toULong(),
            height = heightPx.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = pixels.rowBytes.toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
        )
        CGColorSpaceRelease(colorSpace)
        if (context == null) {
            bitmap.close()
            return null
        }
        try {
            // A page with no background of its own is paper, not transparent black.
            CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0)
            CGContextFillRect(context, CGRectMake(0.0, 0.0, widthPx.toDouble(), heightPx.toDouble()))
            CGContextScaleCTM(context, widthPx / pageWidth, heightPx / pageHeight)
            CGContextTranslateCTM(context, -x, -y)
            CGContextDrawPDFPage(context, page)
        } finally {
            CGContextRelease(context)
        }
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }

    override fun close() {
        val open = document ?: return
        document = null
        CGPDFDocumentRelease(open)
        buffer.close()
    }
}
