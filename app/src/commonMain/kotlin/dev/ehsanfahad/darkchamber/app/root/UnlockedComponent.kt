package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.subscribe
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.backhandler.BackHandlerOwner
import com.arkivanov.essenty.lifecycle.doOnDestroy
import coil3.ImageLoader
import coil3.PlatformContext
import com.arkivanov.essenty.lifecycle.doOnStop
import dev.ehsanfahad.darkchamber.feature.vault.api.AlbumTileName
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryScope
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultAlbumsComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultImports
import dev.ehsanfahad.darkchamber.feature.vault.vaultAlbumsComponent
import dev.ehsanfahad.darkchamber.feature.vault.vaultGalleryComponent
import dev.ehsanfahad.darkchamber.feature.vault.vaultImageLoader
import dev.ehsanfahad.darkchamber.feature.vault.vaultImports
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.app.nowEpochMillis
import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.vault.NoThumbnails
import dev.ehsanfahad.darkchamber.core.vault.ThumbnailEncoder
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The unlocked child — **constructed with a live vault**, which is the structural gate invariant: a
 * decrypted-content screen cannot exist without a [VaultSession]. For now it exposes the [session]
 * and a manual [lock]; the real vault UI is Phase 1.
 *
 * It owns a **Koin session scope** holding the vault's session/storage/catalog (feature UI resolves
 * session-scoped deps from it in Phase 1). Locking — manually **or** on background (Essenty
 * `onStop`) — runs the teardown once: zeroize the MK, purge decrypted `tmp/` scratch, close the
 * catalog driver, dispose the scope, then hand control back to the gate (→ Locked). A session that
 * survives backgrounding is theater (hard rules #1/#5; no driver is left open on lock).
 */
interface UnlockedComponent : BackHandlerOwner {
    val session: VaultSession

    /**
     * The gallery's image loader, or `null` when the graph has no `PlatformContext` to build one
     * from — which in practice means a host test. On iOS the platform module binds one; on Android
     * the type *is* `android.content.Context`, so the application context registered at startup is
     * already one and the module must not declare a second (it would resolve to itself).
     *
     * It belongs to the **session**, not to the screen that draws with it: its memory cache holds
     * decrypted previews, and locking has to be able to empty that even while the app is in the
     * background, where a composition may not recompose at all.
     */
    val imageLoader: ImageLoader?

    /** The feature screens living inside the unlocked subtree. */
    val stack: Value<ChildStack<*, UnlockedChild>>

    /**
     * Whether the offer to leave the app is standing — i.e. whether a back press at the vault's own
     * grid has just been swallowed and is waiting for a second one.
     *
     * Published for the chrome to draw and for nothing else: the decision about what the next press
     * does is re-measured from a clock rather than read from this (see [backPressLeaves]), so a
     * notice left on screen by a late coroutine cannot let a press out of the app.
     */
    val leaveNotice: Value<Boolean>

    /** Lock now: tear down and return the gate to locked. */
    fun lock()

    /**
     * Open the album grid, as a sibling of the gallery rather than in place of it: unlocking still
     * lands on the user's files, and a folder list is the second surface.
     */
    fun openAlbums()

    /**
     * Leave the screen on top, if there is one under it.
     *
     * **The stack does not handle back itself.** The animation that draws the transition takes its
     * own `onBack` and calls it when the gesture finishes, so a stack also registered for back would
     * pop twice on one gesture. This is the single entry point, and [backHandler] — inherited from
     * [BackHandlerOwner] — is what the animation watches to drive the gesture.
     */
    fun onBack()
}

/** A screen inside the unlocked subtree. Viewers join this list once the seekable read path exists. */
sealed interface UnlockedChild {
    /**
     * The item grid. The same child serves the whole vault, one album and the unfiled items — which
     * of those is decided when it is constructed, so [scope] is how the chrome above knows which
     * listing it is drawn over: whether to offer the way into albums, and what to say it is above.
     *
     * The feature's own [GalleryScope] rather than a boolean of this module's own. A flag could say
     * "not the whole vault" and no more, which was enough while the chrome only had an entry point
     * to hide and not a place to name — and two spellings of one fact is one of them going stale.
     */
    class Gallery(val component: VaultGalleryComponent, val scope: GalleryScope) : UnlockedChild

    class Albums(val component: VaultAlbumsComponent) : UnlockedChild
}

class DefaultUnlockedComponent(
    componentContext: ComponentContext,
    private val vault: VaultBootstrap.OpenVault,
    private val koin: Koin,
    // Handed to the feature components so host tests can drive them without a platform main looper.
    private val mainContext: CoroutineContext = Dispatchers.Main,
    // Where an import's encrypt runs. Never the main thread — a device run found key derivation
    // there and the UI froze; injected for the same reason as [mainContext].
    private val ioContext: CoroutineContext = Dispatchers.Default,
    // Where a listing's per-row metadata decrypt runs. Off the main thread in production for the
    // same reason the encrypt is, and injected for the same reason as the two above: a host test
    // reading a freshly pushed screen's state otherwise races a background dispatcher it cannot
    // drive, and passes or fails on how much slow work happened to come first.
    private val mapContext: CoroutineContext = Dispatchers.Default,
    // How long the offer to leave stands after a back press at the grid.
    private val leaveWindow: Duration = LEAVE_WINDOW,
    // **Monotonic, and injected.** Monotonic because the app's own `nowEpochMillis()` is a wall
    // clock: a time sync, a timezone change or the user setting the clock moves it, and an offer
    // measured against it would either never expire or expire the instant it was made. Injected for
    // the same reason the dispatchers above are — a test that waits out a window on real elapsed
    // time is a test that passes because the machine was slow.
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val onLock: () -> Unit,
    /**
     * Leave the app. Called on the second back press at the vault's own grid.
     *
     * An **output callback**, like everything else this component is told to do rather than doing
     * itself — and here it has to be: a back press that reached a callback has been consumed, so
     * there is nothing left to re-dispatch, and a component that reached for the activity would have
     * to know what hosts it. The host wires this to finishing; the lock that follows is the ordinary
     * on-background teardown, which is why the notice can honestly say leaving locks the vault.
     */
    private val onLeave: () -> Unit,
) : UnlockedComponent, ComponentContext by componentContext {

    private val scope: Scope =
        koin.createScope("vault-session-${Random.nextULong()}", named(SESSION_SCOPE)).apply {
            declare(vault.session)
            declare(vault.storage)
            declare(vault.catalog)
            // The streaming object pipeline over this live session. random = the engine's
            // CSPRNG (the file_id / GCM nonce_prefix source); clock = wall time for created_at.
            val engine = koin.get<CryptoEngine>()
            declare(
                VaultObjects(
                    session = vault.session,
                    storage = vault.storage,
                    catalog = vault.catalog,
                    codec = DchmCodec(engine),
                    clock = ::nowEpochMillis,
                    random = engine::randomBytes,
                    mapContext = mapContext,
                    // Optional on purpose. A thumbnail is a cache: resolving the encoder strictly
                    // would turn a platform module that forgot to bind one into a vault that
                    // cannot be opened at all. Without it every item renders its type badge, which
                    // is a state the gallery has to handle anyway. The platform-module tests are
                    // what stop a missing binding going unnoticed.
                    thumbnails = koin.getOrNull<ThumbnailEncoder>() ?: NoThumbnails,
                ),
            )
        }

    /**
     * Built lazily so a graph without a `PlatformContext` — a host test — simply has no loader
     * rather than failing to construct the session. Lazy also means a vault opened and locked
     * without ever showing the gallery never builds one.
     *
     * Held as an explicit [Lazy] rather than a delegate so that [tearDown] can ask whether one was
     * ever built. Reading the value there would *construct* a loader for a session that never drew
     * a tile — allocating a cache in order to empty it.
     */
    private val lazyImageLoader: Lazy<ImageLoader?> = lazy {
        koin.getOrNull<PlatformContext>()?.let { context -> vaultImageLoader(context, scope.get()) }
    }

    override val imageLoader: ImageLoader? get() = lazyImageLoader.value

    /**
     * Parent of every feature coroutine in this session. Cancelling it is the **first** thing lock
     * does, so work that has not yet entered a blocking call stops before the MK is zeroized rather
     * than running on against a dead session.
     *
     * Lock deliberately does not *wait*: `lock()` runs on the UI thread (the button, Android
     * `onStop`, iOS `didEnterBackground`), so awaiting a multi-second import would freeze the app
     * and delay zeroizing the key — the opposite of what hard rule #5 asks for. It does not need to:
     * `VaultObjects` guards the source it streams from, so an encrypt already in flight notices the
     * closed session within a buffered read and aborts, leaving no object, row or scratch behind.
     */
    private val featureWork = SupervisorJob()

    /**
     * The import batch, owned by the **session** rather than by the gallery: navigating
     * away from a screen must not abandon fifty photos half-imported, while locking must stop them.
     * Parented to [featureWork], which lock cancels first, so a lock ends an import before the MK it
     * is encrypting against is zeroized. It holds no session reference of its own beyond the
     * `VaultObjects` in this scope, so closing the scope leaves nothing behind (hard rule #5).
     */
    private val imports: VaultImports =
        vaultImports(
            vaultObjects = scope.get(),
            photoPicker = koin.get(),
            filePicker = koin.get(),
            scope = CoroutineScope(mainContext + featureWork),
            ioContext = ioContext,
        ).also { scope.declare(it) }

    private val navigation = StackNavigation<Config>()

    override val stack: Value<ChildStack<*, UnlockedChild>> =
        childStack(
            source = navigation,
            initialConfiguration = Config.Gallery(),
            // No state preservation, deliberately. A saved stack would be written into the platform's
            // saved-instance state — i.e. to disk — and the configs that will join Gallery carry item
            // ids. There is nothing to restore anyway: the session never survives process death, so a
            // relaunch always starts Locked (hard rules #1/#5).
            serializer = null,
            // **False, and deliberately.** The navigation animation takes its own `onBack` and calls
            // it when a back gesture finishes; a stack registered for back as well would pop twice
            // on one gesture. `onBack` below is the one place a pop happens, and a test drives the
            // host's dispatcher to prove nothing here answers a press on its own.
            handleBackButton = false,
            childFactory = ::createChild,
        )

    private var tornDown = false

    private val _leaveNotice = MutableValue(false)
    override val leaveNotice: Value<Boolean> = _leaveNotice

    /**
     * When the standing offer to leave was made, or null when none is. A [TimeMark] rather than a
     * boolean, because the offer expires by elapsed time and not by anything happening.
     */
    private var leaveArmedAt: TimeMark? = null

    /**
     * Takes the notice back off the screen. Purely cosmetic — see [backPressLeaves] — and parented to
     * [featureWork], so a lock ends it with everything else this session owns.
     */
    private var noticeWork: Job? = null
    private val noticeScope = CoroutineScope(mainContext + featureWork)

    /**
     * Back at the vault's own grid: swallow the first press and offer to leave instead.
     *
     * **Enabled only while the grid is the one screen.** Above it, back belongs to the navigation
     * animation, which registers itself only while there is something to pop and which draws the
     * transition; and above that a live selection keeps winning, because the item grid's callback
     * lives in a child context and so outranks this one. That ordering is why this is registered on
     * the host's handler here at the gate rather than inside a screen.
     */
    private val leaveBack = BackCallback(isEnabled = false) { onBackAtRoot() }

    init {
        // Auto-lock on background. A manual lock() sets tornDown before the ensuing destroy re-fires
        // onStop, so the guard stops a re-entrant second lock; onDestroy is the last-resort net.
        lifecycle.doOnStop { if (!tornDown) lock() }
        lifecycle.doOnDestroy { tearDown() }

        backHandler.register(leaveBack)
        // And give it back: the handler belongs to the host and outlives this subtree, so a callback
        // left on it is one a later press can still reach — and left enabled it would swallow a press
        // meant for the lock screen in order to offer to leave a session that no longer exists. This
        // module has shipped that defect once already.
        lifecycle.doOnDestroy { backHandler.unregister(leaveBack) }

        val navigated = stack.subscribe { current ->
            leaveBack.isEnabled = current.backStack.isEmpty()
            // Any navigation starts over. A press at the grid, a trip into albums and a press on the
            // way back is three presses by one finger, and the third must not confirm the first —
            // the offer was made about a screen the user has since left.
            disarmLeave()
        }
        lifecycle.doOnDestroy(navigated::cancel)
    }

    override val session: VaultSession get() = vault.session

    override fun lock() {
        if (tornDown) return // already locking/locked — signal the gate exactly once
        tearDown()
        onLock()
    }

    private fun tearDown() {
        if (tornDown) return
        tornDown = true
        featureWork.cancel() // stop feature coroutines BEFORE the session they run against dies
        vault.session.close() // zeroize MK (hard rule #5)
        vault.storage.purgeTmp() // wipe decrypted scratch (hard rule #1)
        // The same rule, one layer up: the image loader keeps decrypted previews in memory so the
        // grid does not re-decrypt on every scroll, and they are as much vault content as the
        // scratch just purged. Without this a locked app still holds pictures of the user's files,
        // and nothing about it looks wrong.
        if (lazyImageLoader.isInitialized()) lazyImageLoader.value?.memoryCache?.clear()
        vault.catalog.close() // close the SqlDriver (no leak on lock)
        scope.close() // dispose the session scope
    }

    /**
     * `pushNew`, not `push`: a stack may not hold the same configuration twice, and Decompose throws
     * if asked to. Two taps on this before the first push lands is an ordinary thing for a finger to
     * do, and it must not be a crash.
     */
    override fun openAlbums() = navigation.pushNew(Config.Albums)

    // The one place the stack pops. `pop()` with nothing beneath is a no-op, so leaving the first
    // screen is the host's business rather than something to guard against here.
    override fun onBack() = navigation.pop()

    /**
     * A back press with the grid as the only screen, where there is nothing to pop and the press used
     * to fall through to the system — which backgrounds the app, and backgrounding locks the vault.
     * So the press is answered here: the first one offers to leave, a second one inside the window
     * takes the offer.
     */
    private fun onBackAtRoot() {
        if (backPressLeaves(leaveArmedAt?.elapsedNow(), leaveWindow)) {
            disarmLeave()
            onLeave()
            return
        }
        leaveArmedAt = timeSource.markNow()
        _leaveNotice.value = true
        noticeWork?.cancel()
        noticeWork = noticeScope.launch {
            delay(leaveWindow)
            disarmLeave()
        }
    }

    /** No offer standing, and nothing on screen saying there is. */
    private fun disarmLeave() {
        noticeWork?.cancel()
        noticeWork = null
        leaveArmedAt = null
        _leaveNotice.value = false
    }

    /**
     * The name of the album currently being opened, from the grid that is showing it to the screen
     * being built for it — paired with that album's id, so a name can never reach another screen.
     *
     * **Not in the [Config]**, and that is the whole reason this field exists. Configs are compared,
     * kept in the stack for as long as the screen is, and serialized by any host that preserves
     * state; a decrypted album name belongs in none of that. This is set as the grid's callback
     * fires, read by the child factory the push runs synchronously, and cleared on the way out — so
     * it holds a decrypted name for the length of one navigation and nothing restores or persists
     * it. Null leaves the screen to read its own name from the catalog, as every other way in does.
     */
    private var openingAlbum: Pair<String, AlbumTileName>? = null

    private fun createChild(config: Config, context: ComponentContext): UnlockedChild =
        when (config) {
            is Config.Gallery -> {
                // VaultObjects comes from the session scope, so the gallery cannot outlive the
                // session; the import batch is the session's, and the gallery only watches it.
                val gallery = vaultGalleryComponent(
                    componentContext = context,
                    vaultObjects = scope.get(),
                    imports = imports,
                    mainContext = mainContext + featureWork,
                    // The same dispatcher the import's encrypt uses, for the same reason: an album
                    // rename or delete is a blocking write, and the main thread is where it would be
                    // felt.
                    ioContext = ioContext,
                    filter = config.filter.toItemFilter(),
                    // What the album grid already knew this album was called, so the bar above has
                    // its name in this first frame rather than a catalog round trip later. Matched
                    // on the id: a name left over from anything else is not this screen's.
                    openedAlbumName = (config.filter as? Config.Filter.Album)?.let { album ->
                        openingAlbum?.takeIf { it.first == album.idHex }?.second
                    },
                    // An album that has been deleted, or that disappeared while being looked at, has
                    // no screen left to be. `pop`, not a push: this child *is* the album, so leaving
                    // it means going back to whatever opened it — which is the album grid.
                    onLeaveAlbum = { navigation.pop() },
                )
                // Read back off the component rather than mapped from the config a second time.
                // The feature decides what its filter means for a screen, and it publishes that
                // once at construction — so this cannot disagree with the screen it labels.
                UnlockedChild.Gallery(component = gallery, scope = gallery.state.value.scope)
            }
            Config.Albums -> UnlockedChild.Albums(
                vaultAlbumsComponent(
                    componentContext = context,
                    vaultObjects = scope.get(),
                    mainContext = mainContext + featureWork,
                    // Tapping an album pushes a gallery filtered to it, so the item grid is one
                    // screen serving three listings rather than three screens to keep in agreement.
                    // `pushNew` for the same reason as the albums entry above, and here it is
                    // reachable with one finger: a double tap on an album tile fires this twice
                    // before the first screen is on top, and two identical configs is an exception
                    // rather than a second screen.
                    onOpenAlbum = { id, name ->
                        val idHex = id.toHex()
                        // Set, pushed, cleared — the push builds the child synchronously, so the
                        // name is read inside this call and nothing holds it afterwards. Cleared
                        // even when the push is dropped as a duplicate.
                        openingAlbum = name?.let { idHex to it }
                        navigation.pushNew(Config.Gallery(Config.Filter.Album(idHex)))
                        openingAlbum = null
                    },
                    onOpenUnfiled = { navigation.pushNew(Config.Gallery(Config.Filter.Unfiled)) },
                ),
            )
        }

    /**
     * A screen's address inside the unlocked subtree. Configs carry **ids only** — never a session,
     * never decrypted bytes — so that a future `Viewer(itemId)` stays safe to hold.
     */
    private sealed interface Config {
        data class Gallery(val filter: Filter = Filter.All) : Config

        data object Albums : Config

        /**
         * Which items a gallery child lists.
         *
         * **The album id is hex, not a `ByteArray`.** Configs are compared for equality — that is how
         * the stack decides whether a push is a new screen — and they are serialized when a host
         * preserves state. `ByteArray` breaks both: its equality is identity, so two configs naming
         * the same album would never match, and an array is not something a config serializer can
         * round-trip. Converted back at the component boundary, which is the only place that needs
         * the bytes.
         */
        sealed interface Filter {
            data object All : Filter

            data object Unfiled : Filter

            data class Album(val idHex: String) : Filter
        }
    }

    private fun Config.Filter.toItemFilter(): ItemFilter =
        when (this) {
            Config.Filter.All -> ItemFilter.All
            Config.Filter.Unfiled -> ItemFilter.Unfiled
            is Config.Filter.Album -> ItemFilter.Album(idHex.hexToBytes())
        }

    private companion object {
        const val SESSION_SCOPE = "VaultSession"
    }
}

/**
 * Hex, both ways, for carrying an id through a config.
 *
 * Hand-rolled rather than the stdlib's `toHexString` / `hexToByteArray`, which are still behind an
 * experimental opt-in: an id crossing a navigation boundary is not the place to take one on, and
 * these are four lines each.
 *
 * Lowercase and fixed-width per byte, so the same id always produces the same string — that matters,
 * because config equality is what the stack uses to decide whether a push is a new screen.
 */
private fun ByteArray.toHex(): String =
    joinToString("") { byte ->
        val value = byte.toInt() and 0xFF
        "${HEX[value shr 4]}${HEX[value and 0x0F]}"
    }

private fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { i ->
        ((HEX.indexOf(this[i * 2]) shl 4) or HEX.indexOf(this[i * 2 + 1])).toByte()
    }

private const val HEX = "0123456789abcdef"
