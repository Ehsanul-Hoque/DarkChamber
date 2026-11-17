package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import dev.ehsanfahad.darkchamber.feature.vault.FakePlayer
import dev.ehsanfahad.darkchamber.feature.vault.FakePlayerDisplay
import dev.ehsanfahad.darkchamber.feature.vault.FakePlayerFactory
import dev.ehsanfahad.darkchamber.feature.vault.FakeRangeReader
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ManualDispatcher
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerState
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerTracks
import dev.ehsanfahad.darkchamber.feature.vault.api.ScreenOrientation
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackSupport
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import dev.ehsanfahad.darkchamber.feature.vault.domain.GalleryControls
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The player page's controls, and what the viewer asks of the screen, over a fake player and a fake
 * display. The listing, newest first, is: 3 (video), 2 (photo), 1 (audio) — so item 3 is page 0.
 */
class ViewerControlsTest {
    private val lifecycle = LifecycleRegistry().apply { resume() }

    private fun id(n: Int) = byteArrayOf(n.toByte(), 0x3C)

    private fun entry(n: Int, name: String, mime: String): VaultItemSummary =
        summary(id(n), readableMeta(name, mime), createdAt = n * 10L)

    private val listing = listOf(
        entry(1, "voice.m4a", "audio/mp4"),
        entry(2, "photo.jpg", "image/jpeg"),
        entry(3, "clip.mp4", "video/mp4"),
    )

    private val factory = FakePlayerFactory()
    private val display = FakePlayerDisplay()

    private fun TestScope.viewer(start: Int = 3, withDisplay: Boolean = true): ViewerComponent<Unit> {
        val items = FakeVaultItems(onOpenRange = { Outcome.Ok(FakeRangeReader()) }).apply { summaries.value = listing }
        return ViewerComponent<Unit>(
            componentContext = DefaultComponentContext(lifecycle),
            observeGallery = ObserveGallery(items),
            observeAlbum = ObserveAlbum(items),
            load = { _: GalleryItem, _: Int -> LoadedPage.Page(ViewerPage.CantShow) },
            shred = { Outcome.Ok(Unit) },
            filter = ItemFilter.All,
            controls = GalleryControls(),
            startId = id(start),
            onBack = {},
            openRange = items::openRange,
            playerFactory = factory,
            display = if (withDisplay) display else null,
            mainContext = UnconfinedTestDispatcher(testScheduler),
            ioContext = UnconfinedTestDispatcher(testScheduler),
            releaseContext = ManualDispatcher(),
        ).also { it.viewportMeasured(1_000, 2_000) }
    }

    private val latest: FakePlayer get() = factory.made.last()

    private fun FakePlayer.report(state: PlayerState) {
        this.state.value = state
    }

    private val videoTracks = PlayerTracks(video = TrackSupport.Playable, audio = TrackSupport.Playable)

    // ---- the centre control ----------------------------------------------------------------------

    @Test
    fun the_centre_control_pauses_a_playing_item_and_plays_a_paused_one() = runTest {
        val viewer = viewer()
        latest.report(PlayerState(playing = true))
        viewer.playPause()
        assertEquals("pause", latest.calls.last())

        latest.report(PlayerState(playing = false))
        viewer.playPause()
        assertEquals("play", latest.calls.last())
    }

    @Test
    fun the_centre_control_starts_a_finished_item_again_from_the_beginning() = runTest {
        val viewer = viewer()
        latest.report(PlayerState(positionMs = 60_000, durationMs = 60_000, ended = true))
        latest.calls.clear()

        viewer.playPause()

        assertEquals(listOf("seek:0", "play"), latest.calls)
    }

    // ---- seeking ---------------------------------------------------------------------------------

    @Test
    fun a_seek_stays_between_the_start_and_the_end() = runTest {
        val viewer = viewer()
        latest.report(PlayerState(positionMs = 5_000, durationMs = 60_000))
        latest.calls.clear()

        viewer.seekTo(30_000)
        viewer.seekTo(-1)
        viewer.seekTo(90_000)

        assertEquals(listOf("seek:30000", "seek:0", "seek:60000"), latest.calls)
    }

    @Test
    fun a_skip_moves_ten_seconds_each_way_from_where_it_is_and_stops_at_the_start() = runTest {
        val viewer = viewer()
        latest.report(PlayerState(positionMs = 5_000, durationMs = 60_000))
        latest.calls.clear()

        viewer.skip(forward = true)
        viewer.skip(forward = false)

        assertEquals(listOf("seek:15000", "seek:0"), latest.calls)
    }

    // ---- speed -----------------------------------------------------------------------------------

    @Test
    fun a_hold_runs_fast_or_slow_and_letting_go_returns_to_the_speed_set() = runTest {
        val viewer = viewer()
        viewer.chooseSpeed(1.5f)
        latest.calls.clear()

        viewer.holdSpeed(faster = true)
        viewer.releaseSpeed()
        viewer.holdSpeed(faster = false)
        viewer.releaseSpeed()

        assertEquals(listOf("speed:2.0", "speed:1.5", "speed:0.5", "speed:1.5"), latest.calls)
    }

    @Test
    fun choosing_a_speed_during_a_hold_waits_for_the_hold_to_end() = runTest {
        val viewer = viewer()
        viewer.holdSpeed(faster = true)
        latest.calls.clear()

        viewer.chooseSpeed(1.25f)
        assertEquals(listOf("speed:2.0"), latest.calls, "still held")
        assertEquals(1.25f, viewer.state.value.speed, "the setting shows the new speed")

        viewer.releaseSpeed()
        assertEquals("speed:1.25", latest.calls.last())
    }

    @Test
    fun a_hold_does_not_outlive_its_page() = runTest {
        val viewer = viewer()
        viewer.holdSpeed(faster = true)

        viewer.showPage(2) // the audio item, its finger never lifted on this page
        assertEquals("speed:1.0", latest.calls.first { it.startsWith("speed:") })

        viewer.chooseSpeed(1.25f)
        assertEquals("speed:1.25", latest.calls.last(), "no hold is left over from the page before")
    }

    // ---- volume ----------------------------------------------------------------------------------

    @Test
    fun the_volume_stays_between_nothing_and_full() = runTest {
        val viewer = viewer()
        latest.calls.clear()

        viewer.setVolume(0.3f)
        viewer.setVolume(1.4f)
        viewer.setVolume(-0.2f)

        assertEquals(listOf("volume:0.3", "volume:1.0", "volume:0.0"), latest.calls)
    }

    @Test
    fun a_volume_slide_starts_from_the_level_as_it_is_now() = runTest {
        val viewer = viewer()

        latest.level = 0.3f
        assertEquals(0.3f, viewer.volume())
        latest.level = 0.8f // the phone's keys, between two slides
        assertEquals(0.8f, viewer.volume())
    }

    @Test
    fun with_nothing_playing_there_is_no_volume_to_start_from() = runTest {
        assertNull(viewer(start = 2).volume(), "a photo")
    }

    // ---- the screen ------------------------------------------------------------------------------

    @Test
    fun without_a_display_there_is_no_orientation_button_and_no_brightness() = runTest {
        val viewer = viewer(withDisplay = false)

        assertNull(viewer.state.value.orientationTarget)
        assertNull(viewer.brightness())
    }

    @Test
    fun the_brightness_a_slide_starts_from_is_the_screens() = runTest {
        display.level = 0.35f
        assertEquals(0.35f, viewer().brightness())
    }

    @Test
    fun the_button_turns_the_screen_and_then_names_the_way_back() = runTest {
        val viewer = viewer()
        assertEquals(ScreenOrientation.Landscape, viewer.state.value.orientationTarget)
        assertTrue(display.calls.none { it.startsWith("orientation") }, "nothing turns until pressed")

        viewer.switchOrientation()

        assertEquals("orientation:Landscape", display.calls.last())
        assertEquals(ScreenOrientation.Portrait, viewer.state.value.orientationTarget)
    }

    @Test
    fun another_kind_of_page_turns_the_screen_back_and_the_video_turns_it_again() = runTest {
        val viewer = viewer()
        viewer.switchOrientation()

        viewer.showPage(1) // the photo
        assertEquals("orientation:AppDefault", display.calls.last())
        viewer.showPage(0)
        assertEquals("orientation:Landscape", display.calls.last())
    }

    @Test
    fun an_audio_page_has_no_orientation_or_brightness_of_its_own() = runTest {
        val viewer = viewer()
        viewer.switchOrientation()

        viewer.showPage(2) // the audio item
        assertEquals("orientation:AppDefault", display.calls.last())

        display.calls.clear()
        viewer.switchOrientation()
        viewer.setBrightness(0.4f)
        assertTrue(display.calls.isEmpty(), "neither the button nor the slide works on audio")
    }

    @Test
    fun a_video_that_turns_out_not_to_play_turns_the_screen_back() = runTest {
        val viewer = viewer()
        viewer.switchOrientation()

        latest.report(PlayerState(tracks = PlayerTracks(video = TrackSupport.Unplayable)))

        assertEquals(ViewerPage.CantPlay, viewer.state.value.pages.values.first())
        assertTrue("orientation:AppDefault" in display.calls)
    }

    @Test
    fun the_slide_sets_the_brightness_and_leaving_the_page_hands_it_back() = runTest {
        val viewer = viewer()

        viewer.setBrightness(0.4f)
        viewer.setBrightness(1.3f)
        assertEquals(listOf("brightness:0.4", "brightness:1.0"), display.calls.filter { it.startsWith("brightness") })

        viewer.showPage(1)
        assertEquals("brightness:null", display.calls.filter { it.startsWith("brightness") }.last())
    }

    @Test
    fun a_note_arriving_on_the_same_video_page_keeps_the_brightness() = runTest {
        val viewer = viewer()
        viewer.setBrightness(0.4f)

        latest.report(PlayerState(tracks = PlayerTracks(video = TrackSupport.Playable, audio = TrackSupport.Unplayable)))

        assertTrue(viewer.state.value.pages.values.any { it is ViewerPage.Media && it.note != null }, "the note arrived")
        assertTrue("brightness:null" !in display.calls, "still the same video page")
    }

    @Test
    fun the_screen_stays_on_only_while_the_item_plays() = runTest {
        viewer()

        latest.report(PlayerState(playing = true, tracks = videoTracks))
        assertEquals("awake:true", display.calls.last())
        latest.report(PlayerState(playing = false, tracks = videoTracks))
        assertEquals("awake:false", display.calls.last())
        latest.report(PlayerState(playing = true, tracks = videoTracks))
        latest.report(PlayerState(playing = true, ended = true, tracks = videoTracks))
        assertEquals("awake:false", display.calls.last(), "a finished item lets the screen sleep")
    }

    @Test
    fun leaving_a_playing_page_lets_the_screen_sleep() = runTest {
        val viewer = viewer()
        latest.report(PlayerState(playing = true, tracks = videoTracks))

        viewer.showPage(1)

        assertTrue("awake:false" in display.calls)
    }

    @Test
    fun closing_puts_the_screen_back_as_it_was() = runTest {
        val viewer = viewer()
        viewer.switchOrientation()
        viewer.setBrightness(0.4f)
        latest.report(PlayerState(playing = true, tracks = videoTracks))
        display.calls.clear()

        viewer.back()

        assertEquals(setOf("brightness:null", "orientation:AppDefault", "awake:false"), display.calls.toSet())
    }

    @Test
    fun a_lock_puts_the_screen_back_as_it_was() = runTest {
        val viewer = viewer()
        viewer.switchOrientation()
        viewer.setBrightness(0.4f)
        latest.report(PlayerState(playing = true, tracks = videoTracks))
        display.calls.clear()

        lifecycle.destroy()

        assertEquals(setOf("brightness:null", "orientation:AppDefault", "awake:false"), display.calls.toSet())
    }
}
