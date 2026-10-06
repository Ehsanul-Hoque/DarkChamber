package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import coil3.ImageLoader
import coil3.PlatformContext
import com.arkivanov.essenty.lifecycle.doOnStop
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.random.nextULong

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
interface UnlockedComponent {
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

    /** Lock now: tear down and return the gate to locked. */
    fun lock()

    /**
     * Open the album grid, as a sibling of the gallery rather than in place of it: unlocking still
     * lands on the user's files, and a folder list is the second surface.
     */
    fun openAlbums()
}

/** A screen inside the unlocked subtree. Viewers join this list once the seekable read path exists. */
sealed interface UnlockedChild {
    /**
     * The item grid. The same child serves the whole vault, one album and the unfiled items — which
     * of those is decided when it is constructed, so [atHome] is how the chrome above knows whether
     * this is the screen the album grid is reached from.
     */
    class Gallery(val component: VaultGalleryComponent, val atHome: Boolean) : UnlockedChild

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
    private val onLock: () -> Unit,
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
            handleBackButton = true,
            childFactory = ::createChild,
        )

    private var tornDown = false

    init {
        // Auto-lock on background. A manual lock() sets tornDown before the ensuing destroy re-fires
        // onStop, so the guard stops a re-entrant second lock; onDestroy is the last-resort net.
        lifecycle.doOnStop { if (!tornDown) lock() }
        lifecycle.doOnDestroy { tearDown() }
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

    private fun createChild(config: Config, context: ComponentContext): UnlockedChild =
        when (config) {
            is Config.Gallery -> UnlockedChild.Gallery(
                // VaultObjects comes from the session scope, so the gallery cannot outlive the
                // session; the import batch is the session's, and the gallery only watches it.
                component = vaultGalleryComponent(
                    componentContext = context,
                    vaultObjects = scope.get(),
                    imports = imports,
                    mainContext = mainContext + featureWork,
                    // The same dispatcher the import's encrypt uses, for the same reason: an album
                    // rename or delete is a blocking write, and the main thread is where it would be
                    // felt.
                    ioContext = ioContext,
                    filter = config.filter.toItemFilter(),
                    // An album that has been deleted, or that disappeared while being looked at, has
                    // no screen left to be. `pop`, not a push: this child *is* the album, so leaving
                    // it means going back to whatever opened it — which is the album grid.
                    onLeaveAlbum = { navigation.pop() },
                ),
                atHome = config.filter == Config.Filter.All,
            )
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
                    onOpenAlbum = { id ->
                        navigation.pushNew(Config.Gallery(Config.Filter.Album(id.toHex())))
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
