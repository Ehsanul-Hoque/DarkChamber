package dev.ehsanfahad.darkchamber.feature.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.ClipFile
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoContainer
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.files.Path

/** A recorded clip held in memory: every open reads it all afresh, and its deletion is seen. */
internal class FakeClipFile(
    private val bytes: ByteArray = ByteArray(1_000) { it.toByte() },
    override val path: Path = Path("tmp/scratch-fake.mp4"),
    override val container: VideoContainer = VideoContainer.Mp4,
) : ClipFile {
    var opens = 0
        private set
    var deleted = false
        private set

    override fun open(): RawSource {
        check(!deleted) { "opened after it was deleted" }
        opens++
        return Buffer().apply { write(bytes) }
    }

    override fun size(): Long? = if (deleted) null else bytes.size.toLong()

    override fun delete() {
        deleted = true
    }
}
