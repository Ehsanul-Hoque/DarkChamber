package dev.ehsanfahad.darkchamber.feature.camera

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureVault
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

/**
 * The camera's view of the vault, recorded rather than encrypted.
 *
 * Each import reads its source **at the moment it is called** and keeps what it read, the way the
 * real import consumes the payload before it returns — so a test can check both what reached the
 * vault and what was left in the caller's arrays afterwards.
 */
internal class FakeCaptureVault : CaptureVault {

    class Imported(
        val kind: Int,
        val name: String,
        val mime: String,
        val albumId: ByteArray?,
        val bytes: ByteArray,
    )

    val imported = mutableListOf<Imported>()

    /** Sources opened by every import, so a test can check they were independent. */
    var opens = 0
        private set

    /** What the next import returns; the default succeeds with a fresh id. */
    var nextResult: () -> Outcome<ByteArray> = { Outcome.Ok(byteArrayOf(imported.size.toByte())) }

    /**
     * What each import's `isActive` said once [duringImport] had run — the real import polls it on
     * every read, so `false` here is an import that would have stopped.
     */
    val activeAfterImports = mutableListOf<Boolean>()

    /** The source factory the last import was handed — kept so a test can read the payload again. */
    var lastOpenSource: (() -> RawSource)? = null
        private set

    /** Lets a test reach into the middle of an import — e.g. to see the payload while it is live. */
    var duringImport: (open: () -> RawSource) -> Unit = {}

    override fun import(
        kind: Int,
        name: String,
        mime: String,
        albumId: ByteArray?,
        isActive: () -> Boolean,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> {
        val counted = { opens++; openSource() }
        lastOpenSource = openSource
        duringImport(counted)
        activeAfterImports += isActive()
        val bytes = Buffer()
        counted().use { source ->
            do {
                val read = source.readAtMostTo(bytes, 8192)
            } while (read >= 0)
        }
        imported += Imported(kind, name, mime, albumId?.copyOf(), bytes.readByteArray())
        return nextResult()
    }
}
