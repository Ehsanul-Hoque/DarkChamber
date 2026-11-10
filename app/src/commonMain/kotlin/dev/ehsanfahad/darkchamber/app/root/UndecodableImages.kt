package dev.ehsanfahad.darkchamber.app.root

import androidx.compose.ui.graphics.ImageBitmap
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerImageDecoder

/**
 * The viewer's decoder when the graph binds none — in practice a host test. It declines everything,
 * so a picture page says it can't be shown rather than the vault failing to open; both platform
 * modules bind a real one, and a test on each says so.
 */
internal object UndecodableImages : ViewerImageDecoder {
    override fun decode(bytes: ByteArray, targetLongEdge: Int): ImageBitmap? = null
}
