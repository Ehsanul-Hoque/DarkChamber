package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.graphics.ImageBitmap
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerDisplay
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerKind
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPlayer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPlayerFactory
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultViewerComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerState
import dev.ehsanfahad.darkchamber.feature.vault.domain.GalleryControls
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.domain.ViewerPosition
import dev.ehsanfahad.darkchamber.feature.vault.domain.following
import dev.ehsanfahad.darkchamber.feature.vault.domain.startPosition
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.ehsanfahad.darkchamber.core.logging.logVaultError
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.domain.AlbumPresence
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerDeleteStage
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerNotice
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import kotlin.coroutines.CoroutineContext

/**
 * The viewer's state holder, generic in the picture type so its rules are tested without a platform
 * bitmap ([DefaultVaultViewerComponent] fixes it to `ImageBitmap`).
 *
 * - **Its own subscription**, through the gallery's pipeline, keyed by the filter alone — so the
 *   sequence is the grid's, and nothing here re-subscribes.
 * - **The page follows its item** as the listing changes ([following]); an item that vanishes is
 *   replaced by its neighbour; an empty listing, or a start item already gone, leaves the viewer.
 * - **A window of three pages** — the one shown and one either side — is loaded, and only once the
 *   viewport is measured. A page leaving the window has its load cancelled and its picture dropped.
 *   Pictures live in [pictures], never in [state].
 * - **Delete** shreds the item shown, once; the listing's own re-emission moves the pager on.
 * - **Leaving** happens once, through [onBack], with the item last shown.
 *
 * Destroyed with the unlocked subtree: its scope is cancelled, every load with it, and every picture
 * is dropped.
 */
internal open class ViewerComponent<T>(
    componentContext: ComponentContext,
    observeGallery: ObserveGallery,
    observeAlbum: ObserveAlbum,
    private val load: suspend (item: GalleryItem, targetLongEdge: Int) -> LoadedPage<T>,
    private val shred: suspend (id: ByteArray) -> Outcome<Unit>,
    filter: ItemFilter,
    controls: GalleryControls,
    private val startId: ByteArray,
    private val onBack: (lastViewedId: ByteArray) -> Unit,
    /** Opens an item for range reads; blocking, so it runs on [ioContext]. */
    private val openRange: (id: ByteArray) -> Outcome<RangeReader>,
    /** The platform's player, or `null` where there is none: a video or audio page then shows its badge. */
    private val playerFactory: VaultPlayerFactory?,
    /** The screen's brightness, orientation and sleep, or `null` where there is none to change. */
    private val display: PlayerDisplay? = null,
    mainContext: CoroutineContext = Dispatchers.Main,
    private val ioContext: CoroutineContext = Dispatchers.Default,
    /** Where a player is let go: never the main thread, so a decoder that blocks cannot hold up a lock. */
    private val releaseContext: CoroutineContext = Dispatchers.Default,
) : ComponentContext by componentContext {
    // SupervisorJob parented to the caller's Job, as the gallery's: the app scopes feature work to the
    // vault session, and cancelling that must cancel this before the session it reads through dies.
    private val scope = CoroutineScope(mainContext + SupervisorJob(mainContext[Job]))

    private val _state = MutableValue(ViewerState())
    val state: Value<ViewerState> = _state

    private val _pictures = MutableValue<Map<String, T>>(emptyMap())
    val pictures: Value<Map<String, T>> = _pictures

    private val _players = MutableValue<Map<String, VaultPlayer>>(emptyMap())
    val players: Value<Map<String, VaultPlayer>> = _players

    /** Positions and the set speed, for the viewer's life only; a lock or a close forgets them. */
    private val memory = PlaybackMemory()

    /** The one player, for the page shown. */
    private var playing: Playing? = null

    /** The page shown's reader being opened, before it has a player. */
    private var opening: Opening? = null

    // Not the component's scope: a release must still run after the destroy that cancels that one.
    private val releaseScope = CoroutineScope(releaseContext + SupervisorJob())

    private class Playing(val key: String, val id: ByteArray, val player: VaultPlayer, val watch: Job)

    private class Opening(val key: String, val job: Job)

    /** When the screen changes and when it goes back; every answer goes to [display]. */
    private val displayRules = DisplayRules()

    /** The page last reported to [displayRules]: its key, and whether it was a video page. */
    private var displayedPage: Pair<String, Boolean>? = null

    /** A half held down, for the page shown only: a new page starts at the speed set. */
    private var held: HeldSpeed? = null

    fun chooseSpeed(speed: Float) {
        if (speed !in PlaybackRules.SPEEDS) return
        memory.chooseSpeed(speed)
        _state.value = _state.value.copy(speed = memory.speed)
        applySpeed()
    }

    fun playPause() {
        val player = playing?.player ?: return
        when (PlaybackRules.centreControl(PlaybackRules.status(player.state.value))) {
            CentreControl.Pause -> player.pause()
            CentreControl.Play -> player.play()
            CentreControl.Replay -> {
                player.seekTo(0L)
                player.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val player = playing?.player ?: return
        player.seekTo(PlaybackRules.skipped(positionMs, 0L, player.state.value.durationMs))
    }

    fun skip(forward: Boolean) {
        val player = playing?.player ?: return
        val now = player.state.value
        val by = if (forward) PlaybackRules.SKIP_MS else -PlaybackRules.SKIP_MS
        player.seekTo(PlaybackRules.skipped(now.positionMs, by, now.durationMs))
    }

    fun holdSpeed(faster: Boolean) {
        if (playing == null) return
        held = if (faster) HeldSpeed.Fast else HeldSpeed.Slow
        applySpeed()
    }

    fun releaseSpeed() {
        held = null
        applySpeed()
    }

    private fun applySpeed() {
        playing?.player?.setSpeed(PlaybackRules.runningSpeed(memory.speed, held))
    }

    fun setVolume(level: Float) {
        playing?.player?.setVolume(level.coerceIn(0f, 1f))
    }

    fun brightness(): Float? = display?.brightness()

    fun setBrightness(level: Float) = send(displayRules.brightnessSlid(level.coerceIn(0f, 1f)))

    fun switchOrientation() {
        send(displayRules.orientationPressed())
        publishOrientationTarget()
    }

    private fun publishOrientationTarget() {
        if (display == null) return
        _state.value = _state.value.copy(orientationTarget = displayRules.buttonTarget)
    }

    /**
     * Tell the rules when the page shown changes — another item, or the same one turning out not to
     * play — and nothing otherwise, since each report hands the brightness back.
     */
    private fun syncDisplay() {
        val shown = position ?: return
        val key = shown.id.toListKey()
        val page = _state.value.pages[key]
        val video = page is ViewerPage.Media && page.kind == PlayerKind.Video
        if (displayedPage == key to video) return
        displayedPage = key to video
        send(displayRules.pageShown(video))
    }

    private fun send(commands: List<DisplayCommand>) {
        val display = display ?: return
        for (command in commands) {
            when (command) {
                is DisplayCommand.Brightness -> display.setBrightness(command.level)
                is DisplayCommand.Orientation -> display.setOrientation(command.orientation)
                is DisplayCommand.KeepScreenOn -> display.keepScreenOn(command.on)
            }
        }
    }

    /** The page shown, by item and index; `null` until the first listing places it. */
    private var position: ViewerPosition.Showing? = null

    /** Set once [onBack] has been called: a viewer leaves once, whatever arrives after. */
    private var closed = false

    /** The long edge to decode at; `null` until the viewport is measured, and nothing loads before. */
    private var target: Int? = null

    /** The load under way or done for each page in the window, by key. */
    private val loads = mutableMapOf<String, Job>()

    init {
        publishOrientationTarget()
        doOnDestroy {
            // Before the scope goes: silences the player and hands its release to its own thread.
            stopPlaying(remember = false)
            // A lock: the screen goes back as it was, the phone-wide brightness on iOS above all.
            send(displayRules.closed())
            scope.cancel()
            loads.clear()
            // The pictures are vault content: drop the references now rather than whenever the
            // component itself is collected.
            _pictures.value = emptyMap()
        }

        // The gallery's own pipeline, keyed by the filter alone; the order and the search are fixed
        // for the viewer's life and applied downstream, so the sequence is exactly the grid's.
        val listing = observeGallery(filter, flowOf(controls)).map { it.items }
        // The grid names an item's album only on the whole vault; inside an album the question does
        // not arise there. Details asks it, so here every item in an album's listing carries that
        // album's name — following a rename, and saying so when the name will not open.
        val named = if (filter is ItemFilter.Album) {
            combine(listing, observeAlbum(filter.id)) { items, presence ->
                val name = (presence as? AlbumPresence.Present)?.name
                items.map { it.inAlbum(name) }
            }
        } else {
            listing
        }
        named
            .catch { cause ->
                // A listing that cannot be read leaves nothing to show. The grid behind reports it.
                logVaultError(LISTING_STAGE, VaultError.Unexpected(cause))
                close(position?.id ?: startId)
            }
            .onEach(::onListing)
            .launchIn(scope)
    }

    private fun onListing(items: List<GalleryItem>) {
        if (closed) return
        val ids = items.map { it.id }
        val next = position?.following(ids) ?: startPosition(startId, ids)
        when (next) {
            ViewerPosition.Close -> close(position?.id ?: startId)
            is ViewerPosition.Showing -> {
                position = next
                _state.value = _state.value.copy(items = items, index = next.index, loading = false)
                refreshWindow()
            }
        }
    }

    fun viewportMeasured(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        target = decodeTarget(widthPx, heightPx)
        refreshWindow()
    }

    fun showPage(index: Int) {
        val items = _state.value.items
        if (closed || index !in items.indices) return
        position = ViewerPosition.Showing(items[index].id, index)
        _state.value = _state.value.copy(index = index)
        refreshWindow()
    }

    /**
     * Keep a load for the page shown and one either side, and nothing else: a load for a page that left
     * the window is cancelled, and its page and picture go with it; a page newly in it starts loading.
     * A page still in the window keeps what it has.
     */
    private fun refreshWindow() {
        val shown = position ?: return
        val items = _state.value.items
        // The page on screen first: loads queue one at a time, and are taken in the order started.
        val window = listOf(shown.index, shown.index + 1, shown.index - 1)
            .filter { it in items.indices }
            .associate { items[it].id.toListKey() to items[it] }

        (loads.keys - window.keys).forEach { key -> loads.remove(key)?.cancel() }
        val pages = _state.value.pages.filterKeys { it in window }.toMutableMap()
        _pictures.value = _pictures.value.filterKeys { it in window }

        // A video or audio page needs no viewport and is never loaded as a picture.
        for ((key, item) in window) {
            val kind = playerKind(item) ?: continue
            if (key !in pages) pages[key] = ViewerPage.Media(kind)
        }
        val target = target
        val starting = if (target == null) {
            emptyMap()
        } else {
            window.filterValues { playerKind(it) == null }.filterKeys { it !in loads }
        }
        starting.keys.forEach { key -> pages[key] = ViewerPage.Loading }
        _state.value = _state.value.copy(pages = pages)
        refreshPlayer(items[shown.index])
        syncDisplay()
        if (target == null) return

        // Registered before started: a load can answer at once, and its answer is only taken from a
        // page that is still in the window — which is what being in [loads] means.
        for ((key, item) in starting) {
            val job = scope.launch(start = CoroutineStart.LAZY) { publish(key, load(item, target)) }
            loads[key] = job
            job.start()
        }
    }

    // Only a page still in the window gets here: leaving it cancels the load, and a cancelled load
    // never returns normally — the loader's `withContext` throws on resuming a cancelled caller.
    private fun publish(key: String, loaded: LoadedPage<T>) {
        when (loaded) {
            is LoadedPage.Shown -> {
                _pictures.value = _pictures.value + (key to loaded.image)
                _state.value = _state.value.copy(pages = _state.value.pages + (key to ViewerPage.Picture))
            }
            is LoadedPage.Page -> _state.value = _state.value.copy(pages = _state.value.pages + (key to loaded.page))
        }
    }

    /** The player this item gets, or `null`: not a video or audio item, or no player on this platform. */
    private fun playerKind(item: GalleryItem): PlayerKind? = if (playerFactory == null) null else mediaKind(item.meta)

    /**
     * One player, for the page shown and no other: a player for a page no longer shown is let go, an
     * open for one is abandoned, and the page shown — a video or audio page not already failed — gets
     * one if it has none.
     */
    private fun refreshPlayer(shown: GalleryItem) {
        val key = shown.id.toListKey()
        if (playing?.key != key) stopPlaying()
        if (opening?.key != key) {
            opening?.job?.cancel()
            opening = null
        }
        if (closed || playing != null || opening != null) return
        if (playerKind(shown) == null || _state.value.pages[key] !is ViewerPage.Media) return
        val job = scope.launch(start = CoroutineStart.LAZY) { open(key, shown) }
        opening = Opening(key, job)
        job.start()
    }

    /**
     * Open [item]'s reader off the main thread and hand it to a new player. The reader is closed here
     * on every path that does not hand it over — a failed open, a page left or a lock while it opened —
     * which is why it is taken inside the hop: a result returned from a cancelled hop is dropped.
     */
    private suspend fun open(key: String, item: GalleryItem) {
        val factory = playerFactory ?: return
        val mime = (item.meta as? GalleryItemMeta.Readable)?.mime ?: return
        var reader: RangeReader? = null
        try {
            val opened = withContext(ioContext) {
                openRange(item.id).also { if (it is Outcome.Ok) reader = it.value }
            }
            when (opened) {
                is Outcome.Err -> setPage(key, pageForOpenFailure(opened.error))
                is Outcome.Ok -> {
                    val player = factory.create(opened.value, mime)
                    reader = null // the player owns it now
                    startPlaying(key, item.id, player)
                }
            }
        } finally {
            reader?.close()
            // Only this open's own slot: an abandoned open can finish after the next one has started.
            if (opening?.job === currentCoroutineContext()[Job]) opening = null
        }
    }

    /** Autoplay: from where it was left (or the start), at the speed set. */
    private fun startPlaying(key: String, id: ByteArray, player: VaultPlayer) {
        val from = memory.startPosition(id)
        if (from > 0L) player.seekTo(from)
        player.setSpeed(memory.speed)
        player.play()
        val watch = scope.launch(start = CoroutineStart.LAZY) {
            player.state.collect { onPlayerState(key, it) }
        }
        playing = Playing(key, id, player, watch)
        _players.value = mapOf(key to player)
        watch.start()
    }

    // Only the playing player reports here: letting one go cancels the job watching it first.
    private fun onPlayerState(key: String, reported: PlayerState) {
        val failure = reported.failure
        if (failure != null) {
            pageForPlayerFailure(failure)?.let { setPage(key, it) }
            stopPlaying(remember = false)
            return
        }
        if (nothingPlays(reported.tracks)) {
            setPage(key, ViewerPage.CantPlay)
            stopPlaying(remember = false)
            return
        }
        val page = _state.value.pages[key] as? ViewerPage.Media ?: return
        send(displayRules.playbackChanged(PlaybackRules.keepsScreenOn(PlaybackRules.status(reported))))
        val note = trackNote(reported.tracks)
        if (page.note != note) setPage(key, page.copy(note = note))
    }

    /**
     * Let the player go: off the screen and silent at once, its position remembered (unless it failed),
     * and its release — which closes its reader — on [releaseContext], never waited for here.
     */
    private fun stopPlaying(remember: Boolean = true) {
        val stopping = playing ?: return
        playing = null
        stopping.watch.cancel()
        held = null
        send(displayRules.playbackChanged(playing = false))
        _players.value = emptyMap()
        if (remember) {
            val last = stopping.player.state.value
            memory.leave(stopping.id, last.positionMs, last.durationMs, last.ended)
        }
        stopping.player.pause()
        releaseScope.launch { stopping.player.release() }
    }

    /**
     * Sets the page shown's page. Only ever the page shown: an open still running is still for it (one
     * for a page left is cancelled), and only the page shown has a player to report.
     */
    private fun setPage(key: String, page: ViewerPage) {
        _state.value = _state.value.copy(pages = _state.value.pages + (key to page))
        syncDisplay()
    }

    fun requestDelete() {
        if (closed || position == null || _state.value.delete != null) return
        _state.value = _state.value.copy(delete = ViewerDeleteStage.Asking, notice = null)
    }

    /**
     * Shred the item shown — read now, so it is the one swiped to — once. The confirmation stays up
     * as "deleting" until the vault answers; a shred cannot be called back, so a cancel is ignored.
     * On success the listing drops the item and [onListing] moves to the one that took its place.
     */
    fun confirmDelete() {
        val id = position?.id ?: return
        if (_state.value.delete != ViewerDeleteStage.Asking) return
        _state.value = _state.value.copy(delete = ViewerDeleteStage.Deleting)
        scope.launch {
            val notice = when (shred(id)) {
                is Outcome.Ok -> ViewerNotice.Deleted
                is Outcome.Err -> ViewerNotice.DeleteFailed
            }
            _state.value = _state.value.copy(delete = null, notice = notice)
        }
    }

    fun cancelDelete() {
        if (_state.value.delete != ViewerDeleteStage.Asking) return
        _state.value = _state.value.copy(delete = null)
    }

    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun back() = close(position?.id ?: startId)

    private fun close(lastViewedId: ByteArray) {
        if (closed) return
        closed = true
        stopPlaying()
        opening?.job?.cancel()
        send(displayRules.closed())
        onBack(lastViewedId)
    }

    private fun GalleryItem.inAlbum(name: AlbumTileName?): GalleryItem =
        GalleryItem(id = id, kind = kind, size = size, createdAt = createdAt, meta = meta, album = name)

    private companion object {
        const val LISTING_STAGE = "viewer.listing"
    }
}

/** The production viewer: pictures are platform bitmaps. */
internal class DefaultVaultViewerComponent(
    componentContext: ComponentContext,
    observeGallery: ObserveGallery,
    observeAlbum: ObserveAlbum,
    load: suspend (item: GalleryItem, targetLongEdge: Int) -> LoadedPage<ImageBitmap>,
    shred: suspend (id: ByteArray) -> Outcome<Unit>,
    filter: ItemFilter,
    controls: GalleryControls,
    startId: ByteArray,
    onBack: (lastViewedId: ByteArray) -> Unit,
    openRange: (id: ByteArray) -> Outcome<RangeReader>,
    playerFactory: VaultPlayerFactory?,
    display: PlayerDisplay?,
    mainContext: CoroutineContext = Dispatchers.Main,
    ioContext: CoroutineContext = Dispatchers.Default,
    releaseContext: CoroutineContext = Dispatchers.Default,
) : ViewerComponent<ImageBitmap>(
    componentContext, observeGallery, observeAlbum, load, shred, filter, controls, startId, onBack,
    openRange, playerFactory, display, mainContext, ioContext, releaseContext,
), VaultViewerComponent
