package dev.ehsanfahad.darkchamber.feature.vault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfOpened
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPageSize
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfBuffer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfDocument
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfRenderer
import kotlinx.io.Buffer
import kotlinx.io.RawSink

/**
 * A picture with a size and nothing behind it: what a drawn page looks like to the viewer, without a
 * platform bitmap (none exists in a host test).
 */
internal class FakeImageBitmap(override val width: Int, override val height: Int) : ImageBitmap {
    override val colorSpace: ColorSpace get() = ColorSpaces.Srgb
    override val hasAlpha: Boolean get() = false
    override val config: ImageBitmapConfig get() = ImageBitmapConfig.Argb8888

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) = Unit

    override fun prepareToDraw() = Unit
}

/**
 * A PDF renderer that records every call in [log], in order, across all its buffers and documents, so
 * a test can read "filled, then opened, then rendered page 2, then closed" as one sequence.
 *
 * - [refusal]: `open()` refuses this way (and releases its buffer, as the contract says).
 * - [pages]: each page's size in points; the document has that many pages.
 * - [unrenderable]: pages whose render returns `null`.
 * - [onRender]: runs inside every render before it returns — a hook for a test that needs something to
 *   happen mid-render (a close, a cancel).
 */
internal class FakePdfRenderer(
    override var supported: Boolean = true,
    var refusal: PdfRefusal? = null,
    var pages: List<PdfPageSize> = listOf(PdfPageSize(595, 842), PdfPageSize(595, 842), PdfPageSize(842, 595)),
    var unrenderable: Set<Int> = emptySet(),
    var onRender: (index: Int) -> Unit = {},
    /** Runs inside `open()` once the document exists, before it is returned — where a swipe or a lock can land. */
    var onOpen: () -> Unit = {},
) : VaultPdfRenderer {
    val log = mutableListOf<String>()

    /** Every buffer handed out, newest last. */
    val buffers = mutableListOf<FakePdfBuffer>()

    override fun newBuffer(sizeHint: Long): VaultPdfBuffer {
        log += "buffer $sizeHint"
        return FakePdfBuffer(this).also { buffers += it }
    }
}

internal class FakePdfBuffer(private val renderer: FakePdfRenderer) : VaultPdfBuffer {
    /** Bytes the sink has taken. */
    var filled = 0L
        private set
    var closed = false
        private set
    var opened = false
        private set

    override val sink: RawSink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            source.skip(byteCount)
            filled += byteCount
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    override fun open(): PdfOpened {
        check(!opened) { "opened twice" }
        opened = true
        renderer.log += "open $filled"
        val refusal = renderer.refusal
        if (refusal != null) {
            close()
            return PdfOpened.Refused(refusal)
        }
        val document = FakePdfDocument(renderer, this)
        renderer.onOpen()
        return PdfOpened.Document(document)
    }

    override fun close() {
        if (closed) return
        closed = true
        renderer.log += "close buffer"
    }
}

internal class FakePdfDocument(private val renderer: FakePdfRenderer, private val buffer: FakePdfBuffer) :
    VaultPdfDocument {
    var closed = false
        private set

    override val pageCount: Int get() = renderer.pages.size

    override fun pageSize(index: Int): PdfPageSize = renderer.pages[index]

    override fun render(index: Int, widthPx: Int): ImageBitmap? {
        check(!closed) { "rendered after close" }
        renderer.log += "render $index@$widthPx"
        renderer.onRender(index)
        if (index in renderer.unrenderable) return null
        val size = renderer.pages[index]
        return FakeImageBitmap(widthPx, (widthPx.toLong() * size.heightPt / size.widthPt).toInt())
    }

    override fun close() {
        if (closed) return
        closed = true
        renderer.log += "close document"
        buffer.close()
    }
}
