package dev.ehsanfahad.darkchamber.feature.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.ClipFiles
import kotlinx.io.files.Path

/** Hands out a fresh in-memory clip per recording, and keeps them so a test can check each. */
internal class FakeClipFiles : ClipFiles {
    val made = mutableListOf<FakeClipFile>()

    override fun newClip(): FakeClipFile =
        FakeClipFile(path = Path("tmp/scratch-${made.size}.mp4")).also { made += it }
}
