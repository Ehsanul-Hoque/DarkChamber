package dev.ehsanfahad.darkchamber.app.camera

import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoContainer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScratchClipsTest {
    private val testDir = Path(SystemTemporaryDirectory, "sct-${Random.nextULong()}")

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    /**
     * The recorder picks its format from the file's extension on some platforms, and the saved item
     * takes its MIME from the clip, so the scratch name and the clip both carry the container asked for.
     */
    @Test
    fun a_clip_is_named_and_marked_for_the_format_it_will_hold() {
        val clips = ScratchClips(VaultStorage(testDir))

        val movie = clips.newClip(VideoContainer.QuickTime)
        val mp4 = clips.newClip(VideoContainer.Mp4)

        assertTrue(movie.path.name.endsWith(".mov"), "a QuickTime clip's scratch name")
        assertEquals(VideoContainer.QuickTime, movie.container)
        assertTrue(mp4.path.name.endsWith(".mp4"), "an MP4 clip's scratch name")
        assertEquals(VideoContainer.Mp4, mp4.container)
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path)
    }
}
