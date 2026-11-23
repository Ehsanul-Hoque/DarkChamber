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
import dev.ehsanfahad.darkchamber.feature.vault.api.FormatNote
import dev.ehsanfahad.darkchamber.feature.vault.api.TextDocument
import dev.ehsanfahad.darkchamber.feature.vault.api.TextKind
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
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerDeleteStage
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerNotice
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import kotlin.coroutines.CoroutineContext
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfOpened
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPageSize
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfPlace
import dev.ehsanfahad.darkchamber.feature.vault.api.PdfRefusal
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfBuffer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfDocument
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPdfRenderer
import dev.ehsanfahad.darkchamber.core.logging.logMeasurement
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.io.RawSink

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
    /** Streams a whole object into a sink, verified; blocking, so it runs on [ioContext]. */
    private val openObject: (id: ByteArray, sink: RawSink) -> Outcome<Unit> = { _, _ ->
        Outcome.Err(VaultError.Unexpected(IllegalStateException("no object reads here")))
    },
    /** The platform's PDF renderer, or `null` where there is none: a PDF page then shows its badge. */
    private val pdfs: VaultPdfRenderer? = null,
    /**
     * Where every call to a PDF buffer or document runs — opening, drawing, closing — **one at a time**:
     * the renderer is not safe from two threads at once. Never the main thread.
     */
    private val pdfContext: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
    /** The array a text file is read into, sized from its stored size; a test keeps it to see it zeroed. */
    private val textBuffer: (size: Int) -> ByteArray = { ByteArray(it) },
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

    private val _pdfPages = MutableValue<Map<Int, ImageBitmap?>>(emptyMap())
    val pdfPages: Value<Map<Int, ImageBitmap?>> = _pdfPages

    private val _texts = MutableValue<Map<String, TextDocument>>(emptyMap())
    val texts: Value<Map<String, TextDocument>> = _texts

    fun toggleFormatted() {
        _state.value = _state.value.copy(formatted = !_state.value.formatted)
    }

    /** The key of the text file whose document is in [texts]; one at a time, for the page shown. */
    private var textShown: String? = null

    /** The text file shown being read, before it is [textShown]. */
    private var textOpening: Opening? = null

    /** Whether this item is shown as text here: decided from its stored details, before anything is read. */
    private fun textItem(item: GalleryItem): Boolean = textKind(item.meta) != null

    /**
     * One text file read, for the page shown and no other: a text file no longer shown is let go, a
     * read for one is abandoned, and the page shown — a text file not already answered — is read.
     */
    private fun refreshText(shown: GalleryItem) {
        val key = shown.id.toListKey()
        if (textShown != null && textShown != key) dropText()
        if (textOpening?.key != key) {
            textOpening?.job?.cancel()
            textOpening = null
        }
        val kind = textKind(shown.meta) ?: return
        if (closed || textOpening != null) return
        // Answered already — shown (a text page) or refused — so not read again while in view.
        if (_state.value.pages[key] != ViewerPage.Loading) return
        when (val plan = textPlan(shown.size)) {
            is OpenPlan.Show -> {
                setPage(key, plan.page)
                return
            }
            OpenPlan.Decode -> Unit
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { readText(key, shown, kind) }
        textOpening = Opening(key, job)
        job.start()
    }

    /**
     * Read [item] whole into an array of exactly its stored size and decode it **only once the vault has
     * verified the whole file**; the array is zeroed on every path, the decoded text being what is kept.
     * A lock or a swipe stops the decrypt within a chunk, and a read that was abandoned publishes nothing:
     * its hop throws on the way out.
     */
    private suspend fun readText(key: String, item: GalleryItem, kind: TextKind) {
        try {
            val result = withContext(ioContext) {
                val into = textBuffer(item.size.toInt())
                try {
                    val sink = ExactArraySink(into)
                    val read = try {
                        openObject(item.id, StopsWhenCancelled(coroutineContext.job, sink))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The vault answers with outcomes; a throw is a session closed under the read, or
                        // more bytes than the item's stored size.
                        Outcome.Err(VaultError.Unexpected(e))
                    }
                    when {
                        read is Outcome.Err -> TextRead.Refused(pageForOpenFailure(read.error))
                        !sink.full -> TextRead.Refused(ViewerPage.CouldntOpen) // fewer bytes than stored
                        else -> when (val decoded = TextDecoding.decode(into)) {
                            DecodedText.NotText -> TextRead.Refused(ViewerPage.NotText)
                            is DecodedText.Text -> {
                                val reading = coroutineContext.job
                                val (formatted, note) = formatted(kind, decoded, item.size) { !reading.isActive }
                                TextRead.Shown(ViewerPage.Text(kind, note), TextDocument(decoded, formatted))
                            }
                        }
                    }
                } finally {
                    into.fill(0)
                }
            }
            when (result) {
                is TextRead.Refused -> setPage(key, result.page)
                is TextRead.Shown -> {
                    textShown = key
                    _texts.value = mapOf(key to result.document)
                    setPage(key, result.page)
                }
            }
        } finally {
            // Only this read's own slot: an abandoned read can finish after the next one has started.
            if (textOpening?.job === currentCoroutineContext()[Job]) textOpening = null
        }
    }

    /**
     * A Markdown or JSON file's formatted view, or the note saying why it is shown only as written;
     * neither for the other kinds. Markdown is formatted up to [MARKDOWN_FORMAT_CAP], within the
     * reader's nesting and time limits, and its parse stops as soon as [cancelled] — a lock or a swipe.
     */
    private fun formatted(kind: TextKind, text: DecodedText.Text, size: Long, cancelled: () -> Boolean): Pair<FormattedText?, FormatNote?> =
        when (kind) {
            TextKind.Markdown -> when {
                size > MARKDOWN_FORMAT_CAP -> null to FormatNote.TooLarge
                else -> MarkdownModel.from(text.whole, cancelled = cancelled)
                    ?.let { FormattedText.Markdown(it) to null }
                    ?: (null to FormatNote.TooComplex)
            }
            TextKind.Json -> when (val indented = JsonIndent.indent(text.whole)) {
                is JsonIndent.Result.Indented -> FormattedText.Json(TextDecoding.lines(indented.text)) to null
                JsonIndent.Result.Invalid -> null to FormatNote.NotJson
                JsonIndent.Result.TooLarge -> null to FormatNote.TooLarge
            }
            TextKind.Plain, TextKind.Code -> null to null
        }

    private sealed interface TextRead {
        class Refused(val page: ViewerPage) : TextRead

        class Shown(val page: ViewerPage.Text, val document: TextDocument) : TextRead
    }

    /** Let the text shown go: its document dropped at once, its page back to waiting to be read again. */
    private fun dropText() {
        val key = textShown ?: return
        textShown = null
        _texts.value = emptyMap()
        if (_state.value.pages[key] is ViewerPage.Text) setPage(key, ViewerPage.Loading)
    }

    /**
     * Draw the PDF shown's pages near the screen ([PdfReading.renderWindow]) at the width they are shown,
     * sharpened by zoom, within what one page may be by its own shape ([PdfReading.renderWidth]); let the
     * rest go. A page already drawn at that width
     * is kept; one drawn at another keeps its picture until the new one arrives. Every render runs on
     * [pdfContext], one at a time.
     */
    fun pdfViewport(visible: IntRange, shownWidthPx: Int, zoom: Float, screenWidthPx: Int) {
        val shown = pdf ?: return
        val window = PdfReading.renderWindow(visible, shown.pageCount)
        (shown.renders.keys - window.toSet()).forEach { shown.renders.remove(it)?.job?.cancel() }
        _pdfPages.value = _pdfPages.value.filterKeys { it in window }
        for (index in window) {
            // Each page by its own shape: a tall page reaches the pixel limit at a narrower width.
            val size = shown.sizes[index]
            val width = PdfReading.renderWidth(shownWidthPx, zoom, screenWidthPx, size.widthPt, size.heightPt)
            val current = shown.renders[index]
            if (current != null && current.width == width) continue
            current?.job?.cancel()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val picture = withContext(pdfContext) {
                    try {
                        shown.document.render(index, width)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null // the seam promises not to throw; one that does could not draw this page
                    }
                }
                // Only into the PDF still shown: a render under way when it was left cannot be stopped.
                if (pdf === shown) _pdfPages.value = _pdfPages.value + (index to picture)
            }
            shown.renders[index] = Render(width, job)
            job.start()
        }
    }

    fun pdfScrolled(page: Int, offsetPx: Int) {
        val shown = pdf ?: return
        pdfPlaces[shown.key] = PdfPlace(page, offsetPx)
    }

    /** The PDF shown, open: one at a time, for the page shown and no other. */
    private var pdf: ShownPdf? = null

    /** The PDF shown being read and opened, before it is [pdf]. */
    private var pdfOpening: Opening? = null

    /** Where each PDF was left, for the viewer's life only; a lock or a close forgets them. */
    private val pdfPlaces = mutableMapOf<String, PdfPlace>()

    private class ShownPdf(val key: String, val document: VaultPdfDocument, val sizes: List<PdfPageSize>) {
        val pageCount: Int get() = sizes.size

        /** The render asked for each page near the screen: its width, and the job drawing it. */
        val renders = mutableMapOf<Int, Render>()
    }

    private class Render(val width: Int, val job: Job)

    /** Whether this item is shown as a PDF here: a PDF by its stored type, on a platform with a renderer. */
    private fun pdfKind(item: GalleryItem): Boolean = pdfs != null && isPdf(item.meta)

    /**
     * One open PDF, for the page shown and no other: a PDF no longer shown is closed, a read for one is
     * abandoned, and the page shown — a PDF not already answered — is read and opened if it is not.
     */
    private fun refreshPdf(shown: GalleryItem) {
        val key = shown.id.toListKey()
        if (pdf?.key != key) closePdf()
        if (pdfOpening?.key != key) {
            pdfOpening?.job?.cancel()
            pdfOpening = null
        }
        val renderer = pdfs ?: return
        if (closed || pdf != null || pdfOpening != null || !pdfKind(shown)) return
        if (_state.value.pages[key] != ViewerPage.Loading) return
        when (val plan = pdfPlan(shown.size, renderer.supported)) {
            is OpenPlan.Show -> {
                setPage(key, plan.page)
                return
            }
            OpenPlan.Decode -> Unit
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { openPdf(key, shown, renderer) }
        pdfOpening = Opening(key, job)
        job.start()
    }

    /**
     * Read [item] whole into a buffer from the platform, and open it **only once the vault has verified
     * the whole file** — the buffer may hold the start of a tampered file until then. The buffer is
     * released here on every path that does not end in a document; a document opened for a page that
     * was left, or a lock, while it opened is closed again — which is why both are taken inside their hops.
     */
    private suspend fun openPdf(key: String, item: GalleryItem, renderer: VaultPdfRenderer) {
        var buffer: VaultPdfBuffer? = null
        var document: VaultPdfDocument? = null
        val started = TimeSource.Monotonic.markNow()
        try {
            val read = withContext(ioContext) {
                val into = renderer.newBuffer(item.size).also { buffer = it }
                try {
                    // A lock or a swipe stops the decrypt within a chunk, not at the end of the file.
                    openObject(item.id, StopsWhenCancelled(coroutineContext.job, into.sink))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The vault answers with outcomes; a throw is a session closed under the read.
                    Outcome.Err(VaultError.Unexpected(e))
                }
            }
            if (read is Outcome.Err) {
                setPage(key, pageForOpenFailure(read.error))
                return
            }
            val filled = started.elapsedNow()
            val opened = withContext(pdfContext) {
                val answer = buffer!!.open()
                buffer = null // a refusal released it; a document owns it
                when (answer) {
                    is PdfOpened.Refused -> null to answer.reason
                    is PdfOpened.Document -> {
                        document = answer.document
                        val sizes = List(answer.document.pageCount) { answer.document.pageSize(it) }
                        sizes to null
                    }
                }
            }
            val (sizes, refusal) = opened
            // Timings only: never the name, the page count or anything else read from the file.
            logMeasurement(
                PDF_OPEN_EVENT,
                "fill_ms" to filled.inWholeMilliseconds,
                "open_ms" to (started.elapsedNow() - filled).inWholeMilliseconds,
            )
            val open = document
            // A document with no pages has nothing to show: the renderer's limit, as a refusal is, and
            // the finally below lets it go.
            if (refusal != null || sizes.isNullOrEmpty() || open == null) {
                setPage(key, pageForPdfRefusal(refusal ?: PdfRefusal.Unreadable))
                return
            }
            document = null // shown now; closed when it is left
            pdf = ShownPdf(key, open, sizes)
            setPage(key, ViewerPage.Pdf(open.pageCount, sizes, pdfPlaces[key] ?: PdfPlace()))
        } finally {
            // Both on the PDF context, like every other call to them — never on this thread.
            buffer?.let { unopened -> releaseScope.launch(pdfContext) { unopened.close() } }
            document?.let { left -> releaseScope.launch(pdfContext) { left.close() } }
            // Only this read's own slot: an abandoned read can finish after the next one has started.
            if (pdfOpening?.job === currentCoroutineContext()[Job]) pdfOpening = null
        }
    }

    /**
     * Let the PDF shown go: its drawn pages dropped at once, its page back to waiting, and its document
     * closed on [pdfContext] — after a render in progress, which cannot be interrupted — never waited for here.
     */
    private fun closePdf() {
        val shown = pdf ?: return
        pdf = null
        // A render still queued never runs; one under way finishes before the close below, and its
        // picture is not taken (the PDF is no longer shown).
        shown.renders.values.forEach { it.job.cancel() }
        _pdfPages.value = emptyMap()
        if (_state.value.pages[shown.key] is ViewerPage.Pdf) setPage(shown.key, ViewerPage.Loading)
        releaseScope.launch(pdfContext) { shown.document.close() }
    }

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

    fun volume(): Float? = playing?.player?.volume()

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
            // Before the scope goes: silences the player and hands its release to its own thread, and
            // hands the PDF shown to its own to close (after a render in progress); a read still under
            // way is cancelled with the scope and releases its buffer itself.
            stopPlaying(remember = false)
            closePdf()
            dropText()
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
        // The phone's auto-rotate turns the screen without asking: the button names what is there now.
        displayRules.screenShaped(landscape = widthPx > heightPx)
        publishOrientationTarget()
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

        // A video or audio page needs no viewport and is never loaded as a picture; nor is a PDF,
        // which waits until it is the page shown and is then read whole (refreshPdf).
        for ((key, item) in window) {
            val kind = playerKind(item)
            if (kind != null && key !in pages) pages[key] = ViewerPage.Media(kind)
            if ((pdfKind(item) || textItem(item)) && key !in pages) pages[key] = ViewerPage.Loading
        }
        val target = target
        val starting = if (target == null) {
            emptyMap()
        } else {
            window.filterValues { playerKind(it) == null && !pdfKind(it) && !textItem(it) }.filterKeys { it !in loads }
        }
        starting.keys.forEach { key -> pages[key] = ViewerPage.Loading }
        _state.value = _state.value.copy(pages = pages)
        refreshPlayer(items[shown.index])
        refreshPdf(items[shown.index])
        refreshText(items[shown.index])
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
            setPage(key, pageForPlayerFailure(failure))
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
        closePdf()
        pdfOpening?.job?.cancel()
        dropText()
        textOpening?.job?.cancel()
        send(displayRules.closed())
        onBack(lastViewedId)
    }

    private fun GalleryItem.inAlbum(name: AlbumTileName?): GalleryItem =
        GalleryItem(id = id, kind = kind, size = size, createdAt = createdAt, meta = meta, album = name)

    private companion object {
        const val LISTING_STAGE = "viewer.listing"
        const val PDF_OPEN_EVENT = "viewer.pdf.open"
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
    openObject: (id: ByteArray, sink: RawSink) -> Outcome<Unit>,
    pdfs: VaultPdfRenderer?,
    pdfContext: CoroutineContext,
) : ViewerComponent<ImageBitmap>(
    componentContext, observeGallery, observeAlbum, load, shred, filter, controls, startId, onBack,
    openRange, playerFactory, display, mainContext, ioContext, releaseContext, openObject, pdfs, pdfContext,
), VaultViewerComponent
