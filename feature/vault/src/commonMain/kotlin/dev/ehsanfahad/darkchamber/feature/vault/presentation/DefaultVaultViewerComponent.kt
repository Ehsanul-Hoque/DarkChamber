package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.ui.graphics.ImageBitmap
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
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
    mainContext: CoroutineContext = Dispatchers.Main,
) : ComponentContext by componentContext {
    // SupervisorJob parented to the caller's Job, as the gallery's: the app scopes feature work to the
    // vault session, and cancelling that must cancel this before the session it reads through dies.
    private val scope = CoroutineScope(mainContext + SupervisorJob(mainContext[Job]))

    private val _state = MutableValue(ViewerState())
    val state: Value<ViewerState> = _state

    private val _pictures = MutableValue<Map<String, T>>(emptyMap())
    val pictures: Value<Map<String, T>> = _pictures

    /** The page shown, by item and index; `null` until the first listing places it. */
    private var position: ViewerPosition.Showing? = null

    /** Set once [onBack] has been called: a viewer leaves once, whatever arrives after. */
    private var closed = false

    /** The long edge to decode at; `null` until the viewport is measured, and nothing loads before. */
    private var target: Int? = null

    /** The load under way or done for each page in the window, by key. */
    private val loads = mutableMapOf<String, Job>()

    init {
        doOnDestroy {
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
        val target = target ?: return
        val shown = position ?: return
        val items = _state.value.items
        val window = (shown.index - 1..shown.index + 1)
            .filter { it in items.indices }
            .associate { items[it].id.toListKey() to items[it] }

        (loads.keys - window.keys).forEach { key -> loads.remove(key)?.cancel() }
        val pages = _state.value.pages.filterKeys { it in window }.toMutableMap()
        _pictures.value = _pictures.value.filterKeys { it in window }

        val starting = window.filterKeys { it !in loads }
        starting.keys.forEach { key -> pages[key] = ViewerPage.Loading }
        _state.value = _state.value.copy(pages = pages)

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
    mainContext: CoroutineContext = Dispatchers.Main,
) : ViewerComponent<ImageBitmap>(
    componentContext, observeGallery, observeAlbum, load, shred, filter, controls, startId, onBack, mainContext,
), VaultViewerComponent
