package dev.ehsanfahad.darkchamber.feature.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.ClipFiles
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoContainer
import kotlinx.io.files.Path

/** Hands out a fresh in-memory clip per recording, and keeps them so a test can check each. */
internal class FakeClipFiles : ClipFiles {
    val made = mutableListOf<FakeClipFile>()

    fun newClip(): FakeClipFile = newClip(VideoContainer.Mp4)

    override fun newClip(container: VideoContainer): FakeClipFile =
        FakeClipFile(path = Path("tmp/scratch-${made.size}.${container.extension}"), container = container)
            .also { made += it }
}
