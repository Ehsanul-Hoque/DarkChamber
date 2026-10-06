package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.ehsanfahad.darkchamber.core.logging.logVaultError
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumChoice
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDelete
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraft
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraftProblem
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumState
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTile
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.Filing
import dev.ehsanfahad.darkchamber.feature.vault.api.FilingDestination
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.api.GallerySelection
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryState
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportStage
import dev.ehsanfahad.darkchamber.feature.vault.api.ImportState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultImports
import dev.ehsanfahad.darkchamber.feature.vault.domain.AlbumPresence
import dev.ehsanfahad.darkchamber.feature.vault.domain.CreateAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.CreateAlbumResult
import dev.ehsanfahad.darkchamber.feature.vault.domain.DeleteAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.FileItems
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbums
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.domain.RenameAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.RenameAlbumResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds

/**
 * The gallery's state holder, in the session gate's presentation style
 * (docs/engineering/PATTERNS.md §3): the component *is* the holder, exposing [Value] plus named
 * intents on a lifecycle-scoped coroutine scope.
 *
 * State holds ids and display metadata only — never decrypted bytes — and the whole component dies
 * with the unlocked subtree on lock, so nothing here needs its own teardown beyond cancelling the
 * scope.
 */
internal class DefaultVaultGalleryComponent(
    componentContext: ComponentContext,
    observeGallery: ObserveGallery,
    private val imports: VaultImports,
    // Album editing. Handed in unconditionally even though only an album-scoped screen can use any of
    // it: what decides that is the filter below, so making these nullable would put the same question
    // in two places and leave a null check at every call site.
    observeAlbum: ObserveAlbum,
    private val renameAlbum: RenameAlbum,
    private val deleteAlbum: DeleteAlbum,
    // Filing, which every listing can do: items are selected from any of the three, and all three can
    // move what they are showing into an album.
    private val observeAlbums: ObserveAlbums,
    private val createAlbum: CreateAlbum,
    private val fileItems: FileItems,
    mainContext: CoroutineContext = Dispatchers.Main,
    // How often a *running* batch is allowed to redraw. The batch reports every buffered read —
    // kilobytes apart — which is far finer than a screen can show or a user can perceive, and
    // recomposing the grid at that rate would cost more than the encrypt does. Throttling here
    // rather than in the core keeps the honest byte count intact for anything else that watches it
    // (spec §6.3). Zero in tests, where every state is worth asserting.
    private val progressInterval: Duration = PROGRESS_INTERVAL,
    // How long a pick has to be taking before it is worth saying so. The picker covers this screen a
    // few hundred milliseconds after it is asked for, so a notice published the instant the button
    // is tapped is read by nobody — it is a flicker under a sheet already on its way up. Held back,
    // it appears only when there is a wait to explain, and the wait that matters is the one *after*
    // the user has chosen, while the system materialises the selection (on iOS, a per-asset iCloud
    // download that can run to seconds). Zero in tests.
    private val pickingDelay: Duration = PICKING_DELAY,
    // How long a stage that cannot be measured has to last before it is worth naming. Short enough
    // that a real wait is explained almost at once, long enough that a local file — which passes
    // through both unmeasurable stages in microseconds — never names either. Zero in tests.
    private val stageGrace: Duration = STAGE_GRACE,
    // What this gallery lists. Fixed for the life of the component: a screen showing one album is a
    // different child from the one showing the whole vault, so changing it is navigation rather than
    // a state change, and the selection below cannot straddle two listings.
    private val filter: ItemFilter = ItemFilter.All,
    // Where leaving an album goes — a successful delete, or the notice shown when it disappeared from
    // elsewhere. A callback rather than navigation performed here, like the album grid's openers: a
    // component that popped the stack itself would have to know the shape of the stack it sits in.
    private val onLeaveAlbum: () -> Unit = {},
) : VaultGalleryComponent, ComponentContext by componentContext {

    /**
     * The album this screen is showing, or `null` on the whole-vault and unfiled listings.
     *
     * The one place the filter is unpacked. Every album action below is guarded on it, which is also
     * what makes those actions no-ops on the two listings that have no album behind them — rather than
     * each needing to know which listing it is on.
     */
    private val albumId: ByteArray? = (filter as? ItemFilter.Album)?.id

    /**
     * The album listing's subscription, live only while the chooser is open.
     *
     * Cancelled when it closes rather than left running: every emission opens one small ciphertext per
     * album, and the listing re-emits on every catalog change — including the move this chooser is
     * about to make.
     */
    private var chooserWork: Job? = null

    // SupervisorJob so one failed intent does not kill the component's other work, but parented to
    // whatever Job the caller passed: the app scopes feature work to the vault session, and
    // cancelling that must cancel this too — before the session it runs against is zeroized.
    private val scope = CoroutineScope(mainContext + SupervisorJob(mainContext[Job]))

    private val _state = MutableValue(
        // The scope is published once, at construction, because that is when it is decided: this
        // screen's identity is its filter, and changing it is a push rather than a state change.
        GalleryState(scope = filter.toScope()),
    )
    override val state: Value<GalleryState> = _state

    // Owned here rather than injected: a selection is this screen's own transient mode, and it must
    // die with the screen on lock like everything else holding item ids.
    private val selection = ItemSelection()

    /**
     * Back leaves selecting mode, and only then — enabled follows the mode, so when nothing is
     * selected back belongs to whoever is navigating.
     */
    private val selectionBack = BackCallback(isEnabled = false) { selection.clear() }

    init {
        doOnDestroy { scope.cancel() }

        backHandler.register(selectionBack)

        // One subscription rather than a copy after each intent: every path that changes the
        // selection publishes through here, including the live-list intersection, which no intent
        // triggers at all. A copy per intent would be one forgotten call away from a stale count.
        val subscription = selection.state.subscribe { selected ->
            selectionBack.isEnabled = selected.active
            _state.value = _state.value.copy(
                selection = GallerySelection(active = selected.active, ids = selection.selectedIds()),
            )
            // A chooser outlives its selection only as an empty question: everything it would have moved
            // has gone — cleared by the user, or shredded out of the listing from elsewhere. Closed
            // here, in the one place every change to the selection passes through.
            if (!selected.active && _state.value.filing is Filing.Choosing) {
                closeChooser()
                publishFiling(null)
            }
        }
        doOnDestroy(subscription::cancel)

        observeGallery(filter)
            // A catalog failure arrives as an exception in the flow, not an Outcome (core:vault's
            // documented contract). Report it: an empty list here would render a broken catalog as
            // an empty vault, which is the fail-closed rule's exact target.
            .catch { cause ->
                logVaultError(LISTING_STAGE, VaultError.Unexpected(cause))
                _state.value = _state.value.copy(loading = false, error = GalleryError.ListingFailed)
            }
            .onEach(::onItems)
            .launchIn(scope)

        // The batch runs on the session, not here: this only watches it. The component can be
        // destroyed and rebuilt mid-import and will simply pick the state back up.
        imports.state
            .holdBackPicking(pickingDelay)
            .throttleProgress(progressInterval)
            // After the throttle, not before: the announcement it makes later is a single frame the
            // throttle would be entitled to drop, having just let the silent version of the same
            // state through.
            .holdBackUnmeasurableStages(stageGrace)
            .onEach(::onImportState)
            .launchIn(scope)

        // Only an album-scoped screen watches an album. Nothing is subscribed on the other two, which
        // is not an optimisation: there is no album to watch, and a listing emission that published an
        // album state would put a heading on a screen that has no album.
        albumId?.let { id ->
            observeAlbum(id)
                // A broken catalog is already reported by the item listing above, which fails at the
                // same moment and for the same reason. Reporting it twice would put two banners on one
                // screen; what this must not do is leave the heading showing a stale name as though
                // nothing were wrong, so the album goes to Gone and the notice explains the exit.
                .catch { cause ->
                    logVaultError(ALBUM_STAGE, VaultError.Unexpected(cause))
                    _state.value = _state.value.copy(album = AlbumState.Gone)
                }
                .onEach(::onAlbum)
                .launchIn(scope)
        }
    }

    // The coordinator holds the double-tap guard: it is the thing that knows whether a batch is
    // already running, and it stays right whether or not this screen was rebuilt in between.
    override fun importPhotos() = imports.importPhotos()

    override fun importFiles() = imports.importFiles()

    override fun cancelImport() = imports.cancel()

    override fun acknowledgeImport() = imports.acknowledge()

    // Forwarded, like every other intent: the batch owns the offer, because the handles behind it
    // must not live on a screen that a lock does not reach.
    override fun requestOriginalDelete() = imports.requestOriginalDelete()

    override fun confirmOriginalDelete() = imports.confirmOriginalDelete()

    override fun keepOriginals() = imports.keepOriginals()

    /**
     * **Refused while a batch is running**, which is the one rule selecting has beyond the selection
     * itself. The grid grows underneath an import, so a selection made then is a selection of a
     * moving target — and the bottom area is already showing the progress card, so a selection bar
     * would have to stack on top of it.
     *
     * Deliberately the simpler rule rather than a two-bar layout nobody asked for; reversible if it
     * reads wrong on a device. Nothing guards the other direction, because it cannot happen: the
     * import affordances are what the selection bar replaces, so there is no way to start a batch
     * while selecting.
     */
    override fun selectItem(id: ByteArray) {
        if (_state.value.importing) return
        selection.start(id)
    }

    override fun toggleItem(id: ByteArray) = selection.toggle(id)

    override fun selectAllItems() = selection.selectAll()

    override fun clearSelection() = selection.clear()

    override fun dismissError() {
        _state.value = _state.value.copy(error = null)
        // A summary the user has acknowledged must not come back the moment the state re-emits.
        imports.acknowledge()
    }

    /**
     * Open the chooser, and keep its list live while it is open.
     *
     * Subscribed here rather than for the life of the screen: an album name is a decrypt, the album
     * listing re-emits on every catalog change, and three of the four gallery screens never open this
     * at all. A snapshot would do for the choosing itself — but not for the create-in-flow, where the
     * album that was just made has to appear in the list it was made from.
     */
    override fun startFiling() {
        if (!_state.value.selection.active || _state.value.selection.count == 0) return
        // Not over a move already running, and not over a rename dialog: the heading's actions stay on
        // screen during selection, so both are reachable.
        if (_state.value.filing is Filing.Moving) return
        if ((_state.value.album as? AlbumState.Present)?.rename != null) return

        publishFiling(Filing.Choosing())

        chooserWork?.cancel()
        chooserWork = observeAlbums()
            // A chooser that cannot list the albums is still a chooser: creating one is the way forward
            // from an empty list, and that is exactly the state a failed listing leaves behind.
            .catch { cause -> logVaultError(CHOOSER_STAGE, VaultError.Unexpected(cause)) }
            .onEach(::onChoices)
            .launchIn(scope)
    }

    override fun chooseAlbum(albumId: ByteArray) {
        if (_state.value.filing !is Filing.Choosing) return
        move(albumId, FilingDestination.Album)
    }

    override fun cancelFiling() {
        if (_state.value.filing !is Filing.Choosing) return
        closeChooser()
        publishFiling(null)
    }

    /**
     * Out of the album, and nowhere else. No chooser and no confirmation: unfiling destroys nothing and
     * is undone by filing the same items again, so a dialog would be asking permission for something
     * that needs none.
     */
    override fun unfileSelection() {
        if (!_state.value.selection.active || _state.value.selection.count == 0) return
        if (_state.value.filing != null) return
        move(albumId = null, destination = FilingDestination.Unfiled)
    }

    override fun startFilingCreate() {
        val choosing = _state.value.filing as? Filing.Choosing ?: return
        if (choosing.creating != null) return
        publishFiling(choosing.copy(creating = AlbumDraft()))
    }

    override fun editFilingAlbumName(name: String) {
        val choosing = _state.value.filing as? Filing.Choosing ?: return
        val draft = choosing.creating ?: return
        if (draft.saving) return
        publishFiling(choosing.copy(creating = draft.copy(name = name, problem = null)))
    }

    /**
     * Create the album, then move the selection into it.
     *
     * A failed **create** leaves the field open with its message, because the name is still worth
     * retrying. A failed **move** after a successful create does not undo the album: it exists, the
     * user asked for it, and deleting it to tidy up would destroy something they can see.
     */
    override fun confirmFilingCreate() {
        val choosing = _state.value.filing as? Filing.Choosing ?: return
        val draft = choosing.creating ?: return
        // The double-tap guard, and the one thing between a double tap and two albums: duplicate names
        // are allowed, so nothing further down would refuse the second create.
        if (draft.saving) return

        publishFiling(choosing.copy(creating = draft.copy(saving = true, problem = null)))

        scope.launch {
            when (val made = createAlbum(draft.name)) {
                is CreateAlbumResult.Created -> move(made.id, FilingDestination.Album)
                is CreateAlbumResult.NameMissing -> failFilingCreate(AlbumDraftProblem.NameMissing)
                is CreateAlbumResult.Failed -> {
                    // A stage name and an error code; never the album's name (hard rule 4).
                    logVaultError(CREATE_STAGE, made.error)
                    failFilingCreate(AlbumDraftProblem.CreateFailed)
                }
            }
        }
    }

    override fun cancelFilingCreate() {
        val choosing = _state.value.filing as? Filing.Choosing ?: return
        if (choosing.creating?.saving == true) return
        publishFiling(choosing.copy(creating = null))
    }

    override fun acknowledgeFiling() {
        if (_state.value.filing !is Filing.Moved) return
        publishFiling(null)
    }

    /**
     * The one write both directions go through.
     *
     * The ids are read **before** the move, because clearing the selection afterwards is part of
     * finishing: the items have gone where they were sent, and on an album screen they have left the
     * listing entirely, so a selection still holding them would be a selection of things not on screen.
     */
    private fun move(albumId: ByteArray?, destination: FilingDestination) {
        val ids = selection.selectedIds()
        closeChooser()
        publishFiling(Filing.Moving)

        scope.launch {
            when (val done = fileItems(ids, albumId)) {
                is Outcome.Ok -> {
                    selection.clear()
                    publishFiling(
                        Filing.Moved(
                            requested = done.value.requested,
                            moved = done.value.assigned,
                            destination = destination,
                        ),
                    )
                }
                is Outcome.Err -> {
                    logVaultError(FILING_STAGE, done.error)
                    // The selection survives a refusal: nothing moved, so the user's next move is to
                    // try again rather than to pick everything out of a grid a second time.
                    publishFiling(null)
                    _state.value = _state.value.copy(error = GalleryError.FilingFailed)
                }
            }
        }
    }

    private fun onChoices(albums: List<AlbumTile>) {
        val choosing = _state.value.filing as? Filing.Choosing ?: return
        publishFiling(
            choosing.copy(
                albums = albums
                    // Never the album being viewed. Moving items into the album they are already in is
                    // nothing, and offering it invites a tap that reports a move that did not happen.
                    .filterNot { tile -> albumId?.contentEquals(tile.id) == true }
                    .map { tile -> AlbumChoice(id = tile.id, name = tile.name) },
            ),
        )
    }

    private fun closeChooser() {
        chooserWork?.cancel()
        chooserWork = null
    }

    private fun failFilingCreate(problem: AlbumDraftProblem) {
        val choosing = _state.value.filing as? Filing.Choosing ?: return
        val draft = choosing.creating ?: return
        publishFiling(choosing.copy(creating = draft.copy(saving = false, problem = problem)))
    }

    private fun publishFiling(filing: Filing?) {
        _state.value = _state.value.copy(filing = filing)
    }

    /**
     * Prefilled with the current name, and **only** when there is one to prefill: an unreadable name
     * has nothing to start from, and offering to replace a name the user has never seen is offering an
     * edit they cannot judge. Deleting such an album is still available, which is the action that
     * actually helps there.
     */
    override fun startAlbumRename() {
        val present = _state.value.album as? AlbumState.Present ?: return
        if (present.rename != null || present.delete != null) return
        val current = present.name as? AlbumTileName.Readable ?: return
        publishAlbum(present.copy(rename = AlbumDraft(name = current.value)))
    }

    override fun editAlbumName(name: String) {
        val present = _state.value.album as? AlbumState.Present ?: return
        val draft = present.rename ?: return
        // Nothing is editable while the write runs: accepting a keystroke would rename the album to
        // one thing while the field showed another.
        if (draft.saving) return
        publishAlbum(present.copy(rename = draft.copy(name = name, problem = null)))
    }

    override fun confirmAlbumRename() {
        val id = albumId ?: return
        val present = _state.value.album as? AlbumState.Present ?: return
        val draft = present.rename ?: return
        // The double-submit guard. Two renames would both land, and the second would win — harmless
        // here, unlike the create's duplicate album, but a second write for no reason either way.
        if (draft.saving) return

        publishAlbum(present.copy(rename = draft.copy(saving = true, problem = null)))

        scope.launch {
            when (val done = renameAlbum(id, draft.name)) {
                // Closed. The new name arrives through the album listing, like every other change to
                // the catalog — nothing here writes it into the heading.
                is RenameAlbumResult.Renamed -> closeRename()
                is RenameAlbumResult.NameMissing -> failRename(AlbumDraftProblem.NameMissing)
                is RenameAlbumResult.Failed -> {
                    // A stage name and an error code. Never the name being set, and never the old one
                    // (hard rule 4).
                    logVaultError(RENAME_STAGE, done.error)
                    failRename(AlbumDraftProblem.RenameFailed)
                }
            }
        }
    }

    override fun cancelAlbumRename() {
        val present = _state.value.album as? AlbumState.Present ?: return
        if (present.rename?.saving == true) return
        publishAlbum(present.copy(rename = null))
    }

    /**
     * Opens the confirmation and writes nothing.
     *
     * The confirmation is not ceremony here: deleting an album keeps every file in it, which is the
     * **opposite** of what folder semantics lead people to expect, and this is the only place that
     * expectation gets corrected before the fact.
     */
    override fun requestAlbumDelete() {
        val present = _state.value.album as? AlbumState.Present ?: return
        // Not over a rename, and not over a delete already running.
        if (present.rename != null || present.delete == AlbumDelete.Deleting) return
        publishAlbum(present.copy(delete = AlbumDelete.Confirming))
    }

    override fun confirmAlbumDelete() {
        val id = albumId ?: return
        val present = _state.value.album as? AlbumState.Present ?: return
        // Only from the confirmation, so nothing can delete an album without the copy having been on
        // screen — and never twice, which would run the second delete against an id already gone.
        if (present.delete != AlbumDelete.Confirming && present.delete != AlbumDelete.Failed) return

        publishAlbum(present.copy(delete = AlbumDelete.Deleting))

        scope.launch {
            when (val done = deleteAlbum(id)) {
                // Leave at once. The album listing this screen watches would reach Gone a moment later
                // anyway, but showing the "no longer here" notice for an album the user just deleted on
                // purpose would be explaining their own action back to them.
                is Outcome.Ok -> onLeaveAlbum()
                is Outcome.Err -> {
                    logVaultError(DELETE_STAGE, done.error)
                    failDelete()
                }
            }
        }
    }

    override fun cancelAlbumDelete() {
        val present = _state.value.album as? AlbumState.Present ?: return
        if (present.delete == AlbumDelete.Deleting) return
        publishAlbum(present.copy(delete = null))
    }

    override fun leaveAlbum() = onLeaveAlbum()

    private fun publishAlbum(album: AlbumState) {
        _state.value = _state.value.copy(album = album)
    }

    private fun closeRename() {
        val present = _state.value.album as? AlbumState.Present ?: return
        publishAlbum(present.copy(rename = null))
    }

    /**
     * Read back out of state rather than copying the value captured before the write: the album
     * listing can have re-emitted a new name in the meantime, and reinstating the old one would
     * silently undo a rename on the heading.
     */
    private fun failDelete() {
        val present = _state.value.album as? AlbumState.Present ?: return
        publishAlbum(present.copy(delete = AlbumDelete.Failed))
    }

    private fun failRename(problem: AlbumDraftProblem) {
        val present = _state.value.album as? AlbumState.Present ?: return
        val draft = present.rename ?: return
        // The dialog stays open holding what was typed: both problems are ones the user can act on,
        // and closing it would throw the text away in order to say so.
        publishAlbum(present.copy(rename = draft.copy(saving = false, problem = problem)))
    }

    /**
     * What the album listing says about the album, folded into whatever this screen is doing to it.
     *
     * A rename in progress survives an emission — the listing re-emits for reasons that have nothing
     * to do with this album — but a rename over an album that has **gone** does not: there is nothing
     * left to rename, and a dialog over the notice would be offering an edit that cannot land.
     */
    private fun onAlbum(presence: AlbumPresence) {
        _state.value = _state.value.copy(
            album = when (presence) {
                is AlbumPresence.Gone -> AlbumState.Gone
                is AlbumPresence.Present -> when (val current = _state.value.album) {
                    is AlbumState.Present -> current.copy(name = presence.name)
                    // First emission, or an album that came back — neither carries an action.
                    else -> AlbumState.Present(name = presence.name)
                }
            },
        )
    }

    /**
     * Delays [ImportState.Picking] by [after], and drops it entirely if anything supersedes it in
     * the meantime.
     *
     * `transformLatest`, not `transform`: a new state has to **cancel** the pending delay, so a pick
     * that completes quickly shows no notice at all. With a plain `transform` the delay would merely
     * hold the flow up and the stale notice would still arrive afterwards — briefly drawn on top of
     * a batch that has already started, which is worse than the flicker this exists to remove.
     *
     * Only `Picking` is delayed; a running or finished batch is never held back for any reason.
     */
    private fun Flow<ImportState>.holdBackPicking(after: Duration): Flow<ImportState> =
        transformLatest { value ->
            if (value is ImportState.Picking) delay(after)
            emit(value)
        }

    /**
     * Hold back the *name* of a stage that cannot be measured until it has lasted [after].
     *
     * One rule, not two special cases: **a stage with no fraction to draw is announced only once it
     * has lasted; a stage with a denominator is announced at once**, because a moving bar is its own
     * evidence that something is happening.
     *
     * Both unmeasurable stages need it. Waiting for a local file takes microseconds, and every import
     * ends with a preview attempt whether or not a preview is possible — so without this, a batch of
     * twenty-five local photos would flash two labels per file, fifty in a few seconds.
     *
     * `transformLatest`, for the same reason as [holdBackPicking]: a new state must **cancel** the
     * pending announcement, so a stage that did not last is never named at all. The silent version
     * goes out immediately, so the card keeps its file, its count and its indeterminate bar
     * throughout — exactly as it looked before stages existed.
     *
     * **Only a stage that is new is held back.** A batch re-reports the stage it is already in — the
     * stop arriving is one such re-report, and during a long fetch it is the likeliest one — and
     * holding those back would take a label the user is reading back off the screen for [after]
     * before restoring it. The grace exists to keep a label from flashing on, not to make one flash
     * off, so an announcement already made stands until the stage actually changes.
     */
    private fun Flow<ImportState>.holdBackUnmeasurableStages(after: Duration): Flow<ImportState> =
        flow {
            // Inside the returned flow, not captured beside it: a flow may be collected more than
            // once, and two collectors sharing one `announced` would each see the other's stages.
            var announced: ImportStage? = null
            transformLatest { value ->
                val stage = (value as? ImportState.Running)?.stage
                when {
                    // Measurable, or nothing to name: there is no announcement pending and none owed.
                    stage == null || stage.measurable -> {
                        announced = null
                        emit(value)
                    }
                    // Already on screen. Saying it again costs nothing; taking it away costs the
                    // explanation for a wait that is still going on.
                    stage == announced -> emit(value)
                    else -> {
                        emit(value.copy(stage = null))
                        delay(after)
                        announced = stage
                        emit(value)
                    }
                }
            }.collect { emit(it) }
        }

    /**
     * Let every state through except a flood of [ImportState.Running] updates, of which one per
     * [interval] is enough. A batch ending — or starting, or being cancelled — is never delayed:
     * those are the frames that must land immediately, and there are only ever a handful of them.
     */
    private fun Flow<ImportState>.throttleProgress(interval: Duration): Flow<ImportState> = flow {
        var lastRunning: TimeMark? = null
        var wasStopping = false
        var lastStage: ImportStage? = null
        collect { value ->
            if (value is ImportState.Running) {
                val previous = lastRunning
                // The window drops redundant *progress*. A batch turning into stopping is not
                // progress — it is the user's tap arriving, and it may be the last thing this batch
                // says for minutes if the platform is inside a blocking read. Held back, it leaves a
                // Stop button on screen that has already been pressed.
                val becameStopping = value.stopping != wasStopping
                // Nor is a change of stage. It is a different thing being done, and the stage the
                // screen is still showing has an announcement pending behind it: drop the change and
                // that announcement arrives anyway, labelling the import with a stage it has already
                // left. A bar that says it is waiting for a file it is busy encrypting is worse than
                // a bar that says nothing.
                val changedStage = value.stage != lastStage
                if (becameStopping || changedStage || previous == null || previous.elapsedNow() >= interval) {
                    lastRunning = TimeSource.Monotonic.markNow()
                    wasStopping = value.stopping
                    // Updated only alongside an emission, which today is indistinguishable from
                    // updating it on every report: a changed stage always passes, so the last stage
                    // reported and the last stage shown cannot diverge. Kept here because it is the
                    // placement that survives the bypass ever being narrowed — not because anything
                    // currently depends on it, and a test cannot tell the two apart.
                    lastStage = value.stage
                    emit(value)
                }
            } else {
                lastRunning = null
                wasStopping = false
                lastStage = null
                emit(value)
            }
        }
    }

    private fun onImportState(batch: ImportState) {
        // The batch state goes into GalleryState whole rather than being flattened into flags: the
        // screen needs the file count, the name and the fraction, and reducing them here would mean
        // inventing a second vocabulary for the same thing.
        // Not logged here: this runs again every time the state is replayed to a rebuilt screen,
        // and a failed file is one event. The batch logs it once, where the batch ends.
        val failed = (batch as? ImportState.Finished)?.failed.orEmpty()

        _state.value = _state.value.copy(
            batch = batch,
            error = if (failed.isEmpty()) _state.value.error else GalleryError.ImportFailed,
        )
    }

    private fun onItems(items: List<GalleryItem>) {
        // Before publishing the list, not after: items can be shredded or moved out of the album
        // being viewed while selected, and a selection holding ids the grid no longer shows would
        // count things that are gone and then act on them. The intersection publishes its own state
        // change through the subscription above.
        selection.onItems(items)
        // A successful emission clears a stale listing error; an import error is the user's to
        // dismiss, so it survives.
        val error = _state.value.error.takeIf { it != GalleryError.ListingFailed }
        _state.value = _state.value.copy(items = items, loading = false, error = error)
    }

    private fun ItemFilter.toScope(): GalleryScope =
        when (this) {
            ItemFilter.All -> GalleryScope.WholeVault
            ItemFilter.Unfiled -> GalleryScope.Unfiled
            is ItemFilter.Album -> GalleryScope.Album
        }

    private companion object {
        // Stage labels for the redaction boundary: event names only, never a name, path or id.
        const val LISTING_STAGE = "vault-gallery-listing"
        const val ALBUM_STAGE = "vault-gallery-album"
        const val RENAME_STAGE = "vault-album-rename"
        const val DELETE_STAGE = "vault-album-delete"
        const val CHOOSER_STAGE = "vault-album-chooser"
        const val CREATE_STAGE = "vault-album-create"
        const val FILING_STAGE = "vault-items-filing"

        /** Roughly six frames at 60 Hz: fast enough to look continuous, slow enough to be free. */
        val PROGRESS_INTERVAL: Duration = 100.milliseconds

        /**
         * Longer than a picker takes to cover the screen, short enough that a real wait is explained
         * before it is worried about. Under it, nothing is drawn at all — a notice nobody can finish
         * reading is worse than no notice, and the same warning appears under the progress bar a
         * moment later anyway.
         */
        val PICKING_DELAY: Duration = 450.milliseconds

        /**
         * A wait worth naming is one that lasted.
         *
         * Below this, an import is not waiting on anything a person would notice: a local file is
         * handed over and previewed in microseconds, and naming either stage would put two labels on
         * screen per file — fifty of them across a batch of twenty-five photos, none legible. Above
         * it, something really is holding the import up, and saying which of the two it is turns a
         * bar that cannot move into an explanation.
         */
        val STAGE_GRACE: Duration = 400.milliseconds
    }
}
