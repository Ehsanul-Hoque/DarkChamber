package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import dev.ehsanfahad.darkchamber.feature.vault.FakePlayer
import dev.ehsanfahad.darkchamber.feature.vault.FakePlayerFactory
import dev.ehsanfahad.darkchamber.feature.vault.FakeRangeReader
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.ManualDispatcher
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerFailure
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerKind
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerState
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerTracks
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackNote
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
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The viewer playing the video or audio page it shows, over a fake player and a fake reader, with every
 * dispatcher injected. The listing, newest first, is: 4 (video), 3 (photo), 2 (audio), 1 (video) — so
 * item 4 is page 0 and item 1 is page 3.
 */
class ViewerPlaybackTest {
    private val lifecycle = LifecycleRegistry().apply { resume() }

    private fun id(n: Int) = byteArrayOf(n.toByte(), 0x5A)

    private fun key(n: Int) = id(n).toListKey()

    private fun index(n: Int) = mapOf(4 to 0, 3 to 1, 2 to 2, 1 to 3).getValue(n)

    private fun entry(n: Int, name: String, mime: String): VaultItemSummary =
        summary(id(n), readableMeta(name, mime), createdAt = n * 10L)

    private val listing = listOf(
        entry(1, "clip-1.mp4", "video/mp4"),
        entry(2, "song.m4a", "audio/mp4"),
        entry(3, "photo.jpg", "image/jpeg"),
        entry(4, "clip-4.mp4", "video/mp4"),
    )

    /** Every reader handed out, in order. */
    private val readers = mutableListOf<FakeRangeReader>()

    private val factory = FakePlayerFactory()

    /** Where players are let go; nothing runs there until a test drains it. */
    private val release = ManualDispatcher()

    /** Ids an image load was asked for: a video or audio page must never be loaded as a picture. */
    private val loaded = mutableListOf<List<Byte>>()

    /** Runs inside an open, after the reader exists — where a swipe or a lock can land mid-open. */
    private var duringOpen: () -> Unit = {}

    private fun items(
        openRange: (ByteArray) -> Outcome<RangeReader> = {
            val reader = FakeRangeReader().also { readers += it }
            duringOpen()
            Outcome.Ok(reader)
        },
    ) = FakeVaultItems(onOpenRange = openRange).apply { summaries.value = listing }

    private fun TestScope.viewer(
        start: Int,
        items: FakeVaultItems = items(),
        players: FakePlayerFactory? = factory,
        io: CoroutineContext = UnconfinedTestDispatcher(testScheduler),
    ) = ViewerComponent(
        componentContext = DefaultComponentContext(lifecycle),
        observeGallery = ObserveGallery(items),
        observeAlbum = ObserveAlbum(items),
        load = { item: GalleryItem, _: Int ->
            loaded += item.id.toList()
            LoadedPage.Page(ViewerPage.CantShow)
        },
        shred = { Outcome.Ok(Unit) },
        filter = ItemFilter.All,
        controls = GalleryControls(),
        startId = id(start),
        onBack = {},
        openRange = items::openRange,
        playerFactory = players,
        mainContext = UnconfinedTestDispatcher(testScheduler),
        ioContext = io,
        releaseContext = release,
    ).also { it.viewportMeasured(1_000, 2_000) }

    private fun ViewerComponent<*>.page(n: Int) = state.value.pages[key(n)]

    private fun player(i: Int): FakePlayer = factory.made[i]

    // ---- which items play ------------------------------------------------------------------------

    @Test
    fun a_stored_video_or_audio_type_plays_and_nothing_else_does() {
        assertEquals(PlayerKind.Video, mediaKind(GalleryItemMeta.Readable("a.mp4", "video/mp4")))
        assertEquals(PlayerKind.Video, mediaKind(GalleryItemMeta.Readable("a.mov", "video/quicktime")))
        assertEquals(PlayerKind.Audio, mediaKind(GalleryItemMeta.Readable("a.m4a", "audio/mp4")))
        assertEquals(PlayerKind.Audio, mediaKind(GalleryItemMeta.Readable("a.mp3", "audio/mpeg")))
        assertNull(mediaKind(GalleryItemMeta.Readable("a.jpg", "image/jpeg")))
        assertNull(mediaKind(GalleryItemMeta.Readable("a.pdf", "application/pdf")))
        assertNull(mediaKind(GalleryItemMeta.Unreadable), "a type that will not open is not guessed at")
    }

    // ---- opening and autoplay --------------------------------------------------------------------

    @Test
    fun a_video_page_shown_opens_its_reader_and_a_player_and_plays() = runTest {
        val viewer = viewer(start = 1)

        assertEquals(1, factory.made.size)
        assertEquals("video/mp4", player(0).mime)
        assertTrue(player(0).reader === readers.single(), "the player was handed the reader opened for it")
        assertEquals("play", player(0).calls.last())
        assertEquals(setOf(key(1)), viewer.players.value.keys)
        assertTrue(viewer.players.value.getValue(key(1)) === player(0))
        assertEquals(ViewerPage.Media(PlayerKind.Video), viewer.page(1))
    }

    @Test
    fun an_audio_page_is_an_audio_player_page() = runTest {
        val viewer = viewer(start = 2)

        assertEquals("audio/mp4", player(0).mime)
        assertEquals(ViewerPage.Media(PlayerKind.Audio), viewer.page(2))
    }

    @Test
    fun the_player_does_not_wait_for_the_pictures_viewport() = runTest {
        val items = items()
        val viewer = ViewerComponent(
            componentContext = DefaultComponentContext(lifecycle),
            observeGallery = ObserveGallery(items),
            observeAlbum = ObserveAlbum(items),
            load = { _: GalleryItem, _: Int -> LoadedPage.Page(ViewerPage.CantShow) },
            shred = { Outcome.Ok(Unit) },
            filter = ItemFilter.All,
            controls = GalleryControls(),
            startId = id(1),
            onBack = {},
            openRange = items::openRange,
            playerFactory = factory,
            mainContext = UnconfinedTestDispatcher(testScheduler),
            ioContext = UnconfinedTestDispatcher(testScheduler),
            releaseContext = release,
        )

        assertEquals(setOf(key(1)), viewer.players.value.keys, "no viewport measured, and it plays")
    }

    @Test
    fun only_the_page_shown_has_a_player_its_neighbours_have_none() = runTest {
        val viewer = viewer(start = 2)

        assertEquals(1, factory.made.size)
        assertEquals(ViewerPage.Media(PlayerKind.Video), viewer.page(1), "a neighbour is a player page")
        assertEquals(1, readers.size, "but nothing of it was opened")
        assertEquals(setOf(key(2)), viewer.players.value.keys)
    }

    @Test
    fun a_video_or_audio_page_is_never_loaded_as_a_picture() = runTest {
        viewer(start = 2)

        assertEquals(listOf(id(3).toList()), loaded, "only the photo beside it")
    }

    @Test
    fun without_a_platform_player_a_video_page_is_loaded_as_before_and_shows_its_badge() = runTest {
        val viewer = viewer(start = 1, players = null)

        assertTrue(id(1).toList() in loaded, "the image path decides its page, as before playback existed")
        assertTrue(readers.isEmpty())
        assertTrue(viewer.players.value.isEmpty())
    }

    @Test
    fun an_item_never_played_starts_from_the_beginning_at_normal_speed() = runTest {
        viewer(start = 1)

        assertEquals(listOf("speed:1.0", "play"), player(0).calls)
    }

    // ---- leaving and coming back -------------------------------------------------------------------

    @Test
    fun leaving_the_page_pauses_it_and_lets_it_go_off_the_main_thread() = runTest {
        val viewer = viewer(start = 1)

        viewer.showPage(index(2))

        assertTrue("pause" in player(0).calls)
        assertFalse(player(0).released, "not released on the main thread")
        assertEquals(0, readers[0].closes)
        assertFalse(key(1) in viewer.players.value, "and no longer offered to the screen")

        release.drain()

        assertTrue(player(0).released)
        assertEquals(1, readers[0].closes, "its reader closed")
    }

    @Test
    fun the_page_swiped_to_gets_the_one_player() = runTest {
        val viewer = viewer(start = 1)

        viewer.showPage(index(2))

        assertEquals(2, factory.made.size)
        assertEquals(setOf(key(2)), viewer.players.value.keys)
        assertEquals("play", player(1).calls.last())
    }

    @Test
    fun a_photo_page_swiped_to_has_no_player() = runTest {
        val viewer = viewer(start = 2)

        viewer.showPage(index(3))

        assertTrue(viewer.players.value.isEmpty())
        assertEquals(1, factory.made.size)
    }

    @Test
    fun coming_back_resumes_where_it_was_left() = runTest {
        val viewer = viewer(start = 1)
        player(0).state.value = PlayerState(positionMs = 42_000L, durationMs = 111_000L, playing = true)

        viewer.showPage(index(2))
        viewer.showPage(index(1))

        assertEquals(listOf("seek:42000", "speed:1.0", "play"), player(2).calls)
    }

    @Test
    fun coming_back_to_an_item_that_played_to_its_end_starts_it_again() = runTest {
        val viewer = viewer(start = 1)
        player(0).state.value = PlayerState(positionMs = 110_960L, durationMs = 111_000L, ended = true)

        viewer.showPage(index(2))
        viewer.showPage(index(1))

        assertEquals(listOf("speed:1.0", "play"), player(2).calls)
    }

    @Test
    fun a_chosen_speed_applies_now_and_to_the_next_item() = runTest {
        val viewer = viewer(start = 1)

        viewer.chooseSpeed(1.5f)
        assertEquals("speed:1.5", player(0).calls.last())

        viewer.showPage(index(2))
        assertEquals(listOf("speed:1.5", "play"), player(1).calls)
    }

    @Test
    fun a_speed_the_setting_does_not_offer_changes_nothing() = runTest {
        val viewer = viewer(start = 1)

        viewer.chooseSpeed(3f)

        assertEquals(listOf("speed:1.0", "play"), player(0).calls)
    }

    // ---- what playback reports -------------------------------------------------------------------

    @Test
    fun a_read_refused_as_damaged_mid_play_stops_playback_there_and_lets_the_player_go() = runTest {
        val viewer = viewer(start = 1)

        player(0).state.value = PlayerState(failure = PlayerFailure.Reader(VaultError.Crypto.IntegrityFailure))

        assertEquals(ViewerPage.StoppedUnverified, viewer.page(1))
        assertTrue(viewer.players.value.isEmpty(), "nothing after the bad chunk is drawn")
        release.drain()
        assertTrue(player(0).released)
        assertEquals(1, readers[0].closes)

        viewer.viewportMeasured(1_000, 2_000) // a refresh while it is still the page shown
        assertEquals(1, factory.made.size, "a page that failed is not opened again")
        assertEquals(ViewerPage.StoppedUnverified, viewer.page(1))
    }

    @Test
    fun a_decoder_that_declines_the_format_shows_cant_play() = runTest {
        val viewer = viewer(start = 1)

        player(0).state.value = PlayerState(failure = PlayerFailure.Decoder)

        assertEquals(ViewerPage.CantPlay, viewer.page(1))
        assertTrue(viewer.players.value.isEmpty())
    }

    @Test
    fun a_player_reporting_it_was_stopped_leaves_the_page_as_it_was() = runTest {
        val viewer = viewer(start = 1)

        player(0).state.value = PlayerState(failure = PlayerFailure.Stopped)

        assertEquals(ViewerPage.Media(PlayerKind.Video), viewer.page(1), "a stop is not a failure of the item")
        assertTrue(viewer.players.value.isEmpty())
    }

    @Test
    fun tracks_none_of_which_this_device_plays_show_cant_play() = runTest {
        val viewer = viewer(start = 1)

        player(0).state.value = PlayerState(tracks = PlayerTracks(TrackSupport.Unplayable, TrackSupport.Unplayable))

        assertEquals(ViewerPage.CantPlay, viewer.page(1))
        assertTrue(viewer.players.value.isEmpty())
    }

    @Test
    fun a_part_this_device_cannot_play_is_noted_and_the_rest_keeps_playing() = runTest {
        val viewer = viewer(start = 1)

        player(0).state.value = PlayerState(tracks = PlayerTracks(TrackSupport.Unplayable, TrackSupport.Playable))

        assertEquals(ViewerPage.Media(PlayerKind.Video, TrackNote.NoPicture), viewer.page(1))
        assertEquals(setOf(key(1)), viewer.players.value.keys, "still playing")
        assertFalse("pause" in player(0).calls)
    }

    @Test
    fun what_a_let_go_player_reports_afterwards_is_ignored() = runTest {
        val viewer = viewer(start = 1)
        viewer.showPage(index(2))

        player(0).state.value = PlayerState(failure = PlayerFailure.Reader(VaultError.Crypto.IntegrityFailure))

        assertEquals(ViewerPage.Media(PlayerKind.Video), viewer.page(1), "the page beside stays a player page")
    }

    // ---- opening that does not go to plan --------------------------------------------------------

    @Test
    fun an_item_whose_reader_will_not_open_shows_the_open_failures_page() = runTest {
        val viewer = viewer(start = 1, items = items { Outcome.Err(VaultError.Crypto.IntegrityFailure) })

        assertEquals(ViewerPage.Unverified, viewer.page(1))
        assertTrue(factory.made.isEmpty())
    }

    @Test
    fun a_page_left_while_its_reader_opened_closes_that_reader_and_plays_nothing() = runTest {
        val io = ManualDispatcher()
        val viewer = viewer(start = 1, io = io)
        duringOpen = { viewer.showPage(index(3)) }

        io.drain()

        assertEquals(1, readers.size, "the open had started")
        assertEquals(1, readers[0].closes, "and what it opened was closed")
        assertTrue(factory.made.isEmpty())
        assertTrue(viewer.players.value.isEmpty())
    }

    @Test
    fun a_lock_while_a_reader_opened_closes_that_reader() = runTest {
        val io = ManualDispatcher()
        viewer(start = 1, io = io)
        duringOpen = { lifecycle.destroy() }

        io.drain()

        assertEquals(1, readers.size, "the open had started")
        assertEquals(1, readers[0].closes)
        assertTrue(factory.made.isEmpty())
    }

    /**
     * Away and straight back: the first open, abandoned, finishes after the second has started. Its
     * cleanup must not take the second's place, or the next refresh opens a third and two players play.
     */
    @Test
    fun an_abandoned_open_finishing_late_does_not_let_a_second_player_start() = runTest {
        val io = ManualDispatcher()
        val viewer = viewer(start = 1, io = io)
        viewer.showPage(index(3))
        viewer.showPage(index(1))

        io.runNext() // the abandoned open finishes, its reader closed
        viewer.viewportMeasured(1_000, 2_000) // any refresh
        io.drain()

        assertEquals(1, factory.made.size, "one player")
        assertEquals(setOf(key(1)), viewer.players.value.keys)
        assertEquals(readers.size - 1, readers.count { it.closes == 1 }, "every reader but the playing one closed")
    }

    @Test
    fun a_page_left_before_its_open_began_opens_nothing() = runTest {
        val io = ManualDispatcher()
        val viewer = viewer(start = 1, io = io)

        viewer.showPage(index(3))
        io.drain()

        assertTrue(readers.all { it.closes == 1 }, "anything opened was closed")
        assertTrue(factory.made.isEmpty())
    }

    // ---- lock and leaving the viewer ---------------------------------------------------------------

    @Test
    fun a_lock_lets_the_player_go_without_waiting_on_the_main_thread() = runTest {
        val viewer = viewer(start = 1)

        lifecycle.destroy()

        assertTrue(viewer.players.value.isEmpty(), "gone from the screen at once")
        assertTrue("pause" in player(0).calls, "and silent at once")
        assertFalse(player(0).released, "the release itself waits for its own thread")
        assertTrue(release.pending > 0)

        release.drain()

        assertTrue(player(0).released)
        assertEquals(1, readers[0].closes)
    }

    @Test
    fun leaving_the_viewer_lets_the_player_go() = runTest {
        val viewer = viewer(start = 1)

        viewer.back()
        release.drain()

        assertTrue(player(0).released)
        assertTrue(viewer.players.value.isEmpty())
    }

    @Test
    fun leaving_the_viewer_while_a_reader_opens_plays_nothing_and_closes_it() = runTest {
        val io = ManualDispatcher()
        val viewer = viewer(start = 1, io = io)
        duringOpen = { viewer.back() }

        io.drain()

        assertTrue(factory.made.isEmpty(), "nothing starts playing behind a viewer that has left")
        assertEquals(1, readers.single().closes)
    }

    @Test
    fun once_left_nothing_plays_again_before_the_viewer_is_gone() = runTest {
        val viewer = viewer(start = 1)

        viewer.back()
        viewer.viewportMeasured(2_000, 1_000) // the screen turns while the host is taking it away

        assertEquals(1, factory.made.size)
        assertTrue(viewer.players.value.isEmpty())
    }
}
