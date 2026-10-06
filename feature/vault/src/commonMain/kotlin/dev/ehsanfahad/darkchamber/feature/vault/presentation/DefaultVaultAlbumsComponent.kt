package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import dev.ehsanfahad.darkchamber.core.logging.logVaultError
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTile
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumsError
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumsState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultAlbumsComponent
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbums
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.coroutines.CoroutineContext

/**
 * The album grid's state holder, in the same style as the gallery's: the component is the holder,
 * exposing [Value] on a lifecycle-scoped scope, and it dies with the unlocked subtree on lock.
 *
 * State holds ids, counts and album names — no decrypted file bytes — and a name that would not open
 * arrives as an explicit unreadable tile rather than being dropped.
 */
internal class DefaultVaultAlbumsComponent(
    componentContext: ComponentContext,
    observeAlbums: ObserveAlbums,
    mainContext: CoroutineContext = Dispatchers.Main,
    // Where opening an album goes. A callback rather than navigation performed here: a component
    // that pushed screens itself would have to know the shape of the stack it sits in.
    private val onOpenAlbum: (ByteArray) -> Unit,
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
                _state.value = _state.value.copy(loading = false, error = AlbumsError.ListingFailed)
            }
            .onEach(::onAlbums)
            .launchIn(scope)
    }

    override fun openAlbum(id: ByteArray) = onOpenAlbum(id)

    override fun openUnfiled() = onOpenUnfiled()

    override fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun onAlbums(albums: List<AlbumTile>) {
        // A successful emission clears a stale listing error: the catalog answered, so whatever went
        // wrong before is no longer the truth about this screen.
        _state.value = AlbumsState(albums = albums, loading = false, error = null)
    }

    private companion object {
        // An event name for the redaction boundary — never an album name, an id or a path.
        const val LISTING_STAGE = "vault-albums-listing"
    }
}
