package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.ehsanfahad.darkchamber.core.logging.logVaultError
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraft
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumDraftProblem
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTile
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumsError
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumsState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultAlbumsComponent
import dev.ehsanfahad.darkchamber.feature.vault.domain.CreateAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.CreateAlbumResult
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbums
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * The album grid's state holder, in the same style as the gallery's: the component is the holder,
 * exposing [Value] on a lifecycle-scoped scope, and it dies with the unlocked subtree on lock.
 *
 * State holds ids, counts and album names — no decrypted file bytes — and a name that would not open
 * arrives as an explicit unreadable tile rather than being dropped.
 *
 * It also owns the new-album draft, which is transient screen state of exactly that kind: a name
 * being typed, held in memory, gone with the subtree on lock.
 */
internal class DefaultVaultAlbumsComponent(
    componentContext: ComponentContext,
    observeAlbums: ObserveAlbums,
    private val createAlbum: CreateAlbum,
    mainContext: CoroutineContext = Dispatchers.Main,
    // Where opening an album goes. A callback rather than navigation performed here: a component
    // that pushed screens itself would have to know the shape of the stack it sits in.
    //
    // It reports the album's **name** beside its id, for the screen being opened to start from: that
    // screen has to read the name from the catalog, which takes a round trip it spends with no name
    // to draw, while this one is showing the name already. Null when the id is not one this grid is
    // listing, which the screen cannot produce — then nothing is claimed and the screen opened waits
    // for the catalog as it always did.
    private val onOpenAlbum: (ByteArray, AlbumTileName?) -> Unit,
    private val onOpenUnfiled: () -> Unit,
) : VaultAlbumsComponent, ComponentContext by componentContext {

    // SupervisorJob parented to the caller's Job, exactly as the gallery's is: the app scopes
    // feature work to the vault session, and cancelling that must cancel this before the session it
    // reads through is zeroized.
    private val scope = CoroutineScope(mainContext + SupervisorJob(mainContext[Job]))

    private val _state = MutableValue(AlbumsState())
    override val state: Value<AlbumsState> = _state

    init {
        doOnDestroy { scope.cancel() }

        observeAlbums()
            // A catalog failure arrives as an exception in the flow rather than an Outcome — the
            // documented contract of the listing underneath. Reported, because an empty list here
            // would render a broken catalog as a vault with no albums, which is the fail-closed
            // rule's exact target.
            .catch { cause ->
                logVaultError(LISTING_STAGE, VaultError.Unexpected(cause))
                // Two fields: the banner is dismissible, the fact is not. catch ends the flow, so an
                // empty list is all the screen would otherwise have to go on — and an empty list is
                // what a vault with no albums looks like too.
                _state.value = _state.value.copy(
                    loading = false,
                    error = AlbumsError.ListingFailed,
                    listingUnreadable = true,
                )
            }
            .onEach(::onAlbums)
            .launchIn(scope)
    }

    override fun openAlbum(id: ByteArray) =
        onOpenAlbum(id, _state.value.albums.firstOrNull { it.id.contentEquals(id) }?.name)

    override fun openUnfiled() = onOpenUnfiled()

    override fun startCreate() {
        // Already open: leave the draft exactly as it is. The only way to arrive here twice is a tap
        // on an affordance the dialog is covering, and discarding a half-typed name over that would
        // be destroying work to acknowledge a stray touch.
        if (_state.value.draft != null) return
        _state.value = _state.value.copy(draft = AlbumDraft())
    }

    override fun editAlbumName(name: String) {
        val draft = _state.value.draft ?: return
        // Nothing is editable while the create runs, so a keystroke arriving here is a race with the
        // field being disabled — and accepting it would mean the album is created under one name
        // while the dialog shows another.
        if (draft.saving) return
        // The problem goes with the edit: it was about the text that is now being replaced, and a
        // message that outlives what it described reads as a field that refuses to be fixed.
        _state.value = _state.value.copy(draft = draft.copy(name = name, problem = null))
    }

    override fun confirmCreate() {
        val draft = _state.value.draft ?: return
        // The double-tap guard, and the one thing between a double tap and two albums: duplicate
        // names are allowed by design, so nothing further down would refuse the second create.
        if (draft.saving) return

        _state.value = _state.value.copy(draft = draft.copy(saving = true, problem = null))

        scope.launch {
            when (val made = createAlbum(draft.name)) {
                // Closed, and nothing is inserted here: the album arrives through the live listing
                // like any other change to the catalog.
                is CreateAlbumResult.Created -> publish(draft = null)
                is CreateAlbumResult.NameMissing -> publish(
                    draft.copy(saving = false, problem = AlbumDraftProblem.NameMissing),
                )
                is CreateAlbumResult.Failed -> {
                    // The error code and a stage name. **Never the album's name** — it is exactly the
                    // kind of user content hard rule 4 keeps out of logs.
                    logVaultError(CREATE_STAGE, made.error)
                    // The dialog stays open holding what was typed: a failure the user can retry is
                    // worth retrying, and closing it would throw the name away to say so.
                    publish(draft.copy(saving = false, problem = AlbumDraftProblem.CreateFailed))
                }
            }
        }
    }

    override fun cancelCreate() {
        // A create in flight cannot be called back, so the dialog cannot pretend it was: it stays up
        // with its buttons spent until the vault answers.
        if (_state.value.draft?.saving == true) return
        _state.value = _state.value.copy(draft = null)
    }

    override fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun onAlbums(albums: List<AlbumTile>) {
        // A successful emission clears a stale listing error: the catalog answered, so whatever went
        // wrong before is no longer the truth about this screen.
        //
        // The draft is carried across: a listing re-emits on every catalog change, including the
        // create this dialog just made and any import happening elsewhere, and rebuilding the state
        // wholesale would close the dialog out from under whoever is typing in it.
        _state.value = AlbumsState(
            albums = albums,
            loading = false,
            error = null,
            draft = _state.value.draft,
            // A listing that arrives is a listing that was read. Stated rather than left to the
            // default: it is the one thing that disproves the flag, and a reader should not have to
            // check a constructor default to know that an empty list here means empty.
            listingUnreadable = false,
        )
    }

    /** Publish a new draft, leaving everything the listing owns alone. */
    private fun publish(draft: AlbumDraft?) {
        _state.value = _state.value.copy(draft = draft)
    }

    private companion object {
        // Event names for the redaction boundary — never an album name, an id or a path.
        const val LISTING_STAGE = "vault-albums-listing"
        const val CREATE_STAGE = "vault-album-create"
    }
}
