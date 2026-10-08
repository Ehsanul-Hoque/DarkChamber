package dev.ehsanfahad.darkchamber.feature.vault

import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.vault.AlbumSummary
import dev.ehsanfahad.darkchamber.core.vault.AssignSummary
import dev.ehsanfahad.darkchamber.core.vault.ImportPhase
import dev.ehsanfahad.darkchamber.core.vault.ObjectMeta
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import dev.ehsanfahad.darkchamber.feature.vault.domain.VaultItems
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** A picker that hands back whatever the test configured, or cancels, or refuses to open. */
internal class FakeFilePicker(
    private val result: List<PickedFile> = emptyList(),
    private val failure: Throwable? = null,
) : FilePicker {
    constructor(single: PickedFile) : this(listOf(single))

    var calls = 0
        private set

    override suspend fun pickFiles(): List<PickedFile> {
        calls++
        failure?.let { throw it }
        return result
    }
}

/**
 * A photo picker that hands back whatever the test configured, or cancels, or refuses to open.
 * Mirrors [FakeFilePicker] on purpose: the two seams differ in how they present, never in what they
 * return.
 */
internal class FakePhotoPicker(
    private val result: List<PickedFile> = emptyList(),
    private val failure: Throwable? = null,
) : PhotoPicker {
    var calls = 0
        private set

    override suspend fun pickPhotos(): List<PickedFile> {
        calls++
        failure?.let { throw it }
        return result
    }
}

/**
 * A picked file that records whether its platform grant was released.
 *
 * [size] defaults to the payload's real length — the ordinary case, where the platform knows. Pass
 * `null` explicitly for the provider that will not say.
 */
internal class FakePickedFile(
    override val name: String = "photo.jpg",
    override val mime: String = "image/jpeg",
    private val data: ByteArray = byteArrayOf(1, 2, 3),
    override val size: Long? = data.size.toLong(),
    /** Defaults to the common platform answer: most originals cannot be deleted. */
    override val deletable: Boolean = false,
    /**
     * A provider that advertises deletion and then refuses the call — a document that moved, a
     * grant that no longer covers it. The flag is only ever an advertisement.
     */
    private val deleteRefuses: Boolean = false,
) : PickedFile {
    var closed = false
        private set
    var opens = 0
        private set

    /**
     * How often deletion was asked for. Counted rather than flagged so a test can tell "never
     * offered" apart from "offered twice" — the second being a file deleted once per retry.
     */
    var deleteRequests = 0
        private set

    override fun openSource(): RawSource {
        opens++
        return Buffer().apply { write(data) }
    }

    override fun deleteOriginal(): Boolean {
        deleteRequests++
        return deletable && !deleteRefuses
    }

    override fun close() {
        closed = true
    }
}

/**
 * A picked file whose open **blocks** until it is released — the shape of a cloud document whose
 * provider downloads the whole file before returning a descriptor.
 *
 * It spins rather than sleeping because there is no sleep in common code, and it has to occupy a
 * real thread: the behaviour under test is a wait that cannot be interrupted from inside, so a fake
 * that merely suspended would not reproduce it.
 */
internal class BlockingPickedFile(
    override val name: String = "from-the-cloud.mov",
    override val mime: String = "video/quicktime",
    override val size: Long? = 3L,
    /** Set when the "download" finishes and the open is allowed to return. */
    @Volatile var released: Boolean = false,
) : PickedFile {
    var closed = false
        private set

    /** The source the blocked open eventually produced, if it ever got that far. */
    @Volatile
    var delivered: TrackedRawSource? = null
        private set

    override fun openSource(): RawSource {
        @Suppress("ControlFlowWithEmptyBody")
        while (!released) {
            // Busy-waiting on purpose: the point is that this call holds its thread and answers to
            // nothing, which is what makes the open unstoppable from the coroutine that wants it.
        }
        return TrackedRawSource().also { delivered = it }
    }

    override fun close() {
        closed = true
    }
}

/**
 * A picked file that records when its details were read.
 *
 * On Android reading any of these is a cursor query into the provider, so *where* they are read
 * matters as much as what they say — and nothing about the value reveals which thread asked.
 */
internal class RecordingPickedFile(
    private val declaredName: String = "photo.jpg",
    private val declaredMime: String = "image/jpeg",
    private val declaredSize: Long? = 3L,
    private val declaredDeletable: Boolean = true,
    /**
     * Consulted on **every** read, so a test can assert where each one happened rather than only
     * whether one happened yet. Sampling a count at one moment covers the reads before that moment
     * and says nothing about the rest.
     */
    private val onImportDispatcher: () -> Boolean = { true },
) : PickedFile {
    @Volatile
    var columnReads: Int = 0
        private set

    /** Reads that happened anywhere but the import dispatcher — each one a provider query on the UI thread. */
    @Volatile
    var columnReadsElsewhere: Int = 0
        private set

    var closed = false
        private set

    private fun record() {
        columnReads++
        if (!onImportDispatcher()) columnReadsElsewhere++
    }

    override val name: String
        get() {
            record()
            return declaredName
        }

    override val mime: String
        get() {
            record()
            return declaredMime
        }

    override val size: Long?
        get() {
            record()
            return declaredSize
        }

    override val deletable: Boolean
        get() {
            record()
            return declaredDeletable
        }

    override fun openSource(): RawSource = Buffer().apply { write(byteArrayOf(1, 2, 3)) }

    override fun deleteOriginal(): Boolean = declaredDeletable

    override fun close() {
        closed = true
    }
}

/**
 * A dispatcher that queues everything and runs it only when the test says so.
 *
 * `runTest`'s own dispatchers all share one scheduler, so advancing the test clock would run work
 * posted to any of them — which cannot distinguish "this ran somewhere else" from "this ran here".
 * Holding the queue by hand is what makes the distinction observable.
 */
internal class ManualDispatcher : CoroutineDispatcher() {
    private val queued = mutableListOf<Runnable>()

    /** True only while this dispatcher is actually executing something. */
    @Volatile
    var isRunning: Boolean = false
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queued += block
    }

    /** Runs everything queued so far, including whatever those blocks queue in turn. */
    fun drain() {
        isRunning = true
        try {
            while (queued.isNotEmpty()) {
                val batch = queued.toList()
                queued.clear()
                batch.forEach { it.run() }
            }
        } finally {
            isRunning = false
        }
    }

    /**
     * Runs **one** queued block and stops, leaving whatever it queued for the next call.
     *
     * [drain] cannot express a test that needs to act *between* two hops onto this dispatcher,
     * because its loop keeps going until the queue is empty and so runs the second hop too. Stepping
     * one block at a time is what makes the gap between them a place a test can stand.
     */
    fun runNext() {
        val next = queued.removeFirstOrNull() ?: return
        isRunning = true
        try {
            next.run()
        } finally {
            isRunning = false
        }
    }
}

/**
 * A picked file that opens at once and hands out sources whose closing can be checked.
 *
 * [BlockingPickedFile] also delivers a [TrackedRawSource], but only after busy-waiting for a
 * release flag — which is the wrong shape for a test about what happens *after* a successful open.
 */
internal class TrackedPickedFile(
    override val name: String = "photo.jpg",
    override val mime: String = "image/jpeg",
    override val size: Long? = 3L,
    override val deletable: Boolean = false,
) : PickedFile {
    /** Every source this file has produced, in the order it produced them. */
    val opened = mutableListOf<TrackedRawSource>()

    var closed = false
        private set

    override fun openSource(): RawSource = TrackedRawSource().also { opened += it }

    override fun deleteOriginal(): Boolean = false

    override fun close() {
        closed = true
    }
}

/**
 * A picked file that opens fine and then fails to describe itself.
 *
 * Not as contrived as it reads: on Android the name, size and MIME each come from a cursor query
 * into the owning provider, and a provider that has died, revoked the grant or lost its backing
 * document between the open and the query throws from exactly here.
 */
internal class DetailsThrowingPickedFile : PickedFile {
    val opened = mutableListOf<TrackedRawSource>()

    var closed = false
        private set

    override val name: String get() = error("the provider stopped answering")
    override val mime: String get() = "image/jpeg"
    override val size: Long? get() = 3L
    override val deletable: Boolean get() = false

    override fun openSource(): RawSource = TrackedRawSource().also { opened += it }

    override fun deleteOriginal(): Boolean = false

    override fun close() {
        closed = true
    }
}

/** A source that records whether anyone closed it. */
internal class TrackedRawSource : RawSource {
    @Volatile
    var closed: Boolean = false
        private set

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1L

    override fun close() {
        closed = true
    }
}

/**
 * Wait, in real time, for something another thread is expected to do.
 *
 * `runTest`'s clock is virtual, so a `delay` on the test dispatcher would not give a background
 * coroutine any wall-clock time to run in. This hops onto a real dispatcher to do the waiting, and
 * fails loudly rather than hanging if the thing never happens.
 */
internal suspend fun awaitTrue(what: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeoutOrNull(timeoutMillis) {
            while (!condition()) delay(5)
        } ?: error("timed out after ${timeoutMillis}ms waiting for: $what")
    }
}

/**
 * The vault seam the feature actually talks to. Real imports need a session, storage, a catalog and
 * a codec; the feature only cares that it asked for one and what came back.
 */
internal class FakeVaultItems(
    private val importResult: Outcome<ByteArray> = Outcome.Ok(byteArrayOf(7)),
    /** Names that fail instead, so a batch can be given one bad file among good ones. */
    private val failFor: Set<String> = emptySet(),
    /** Cumulative byte counts to report while importing, standing in for the codec's two passes. */
    private val bytesPerImport: List<Long> = emptyList(),
    /**
     * Phase-and-bytes reports to make instead of [bytesPerImport], for a test that cares which
     * phase a count belongs to — a spilled payload, or the preview attempt at the end.
     */
    private val phaseReports: List<Pair<ImportPhase, Long>> = emptyList(),
    /** Runs as each file starts, so a test can cancel or lock partway through a batch. */
    private val onImport: (String) -> Unit = {},
    /** Stored previews by item id (as a list, so the map keys compare by content). */
    private val thumbnails: Map<List<Byte>, Outcome<ByteArray?>> = emptyMap(),
    /** What a create answers. A failure here is the vault refusing, not a name being wrong. */
    private val createResult: Outcome<ByteArray> = Outcome.Ok(byteArrayOf(42)),
    /** What a rename answers. */
    private val renameResult: Outcome<Unit> = Outcome.Ok(Unit),
    /** What a delete answers. */
    private val deleteResult: Outcome<Unit> = Outcome.Ok(Unit),
    /**
     * How many of a filing request actually move, as a function of how many were asked for. The default
     * moves them all; a test about the partial path returns fewer, which is what the catalog does when
     * an id has left it.
     */
    private val assignedOf: (Int) -> Int = { it },
    /** A filing refused outright — a destination album that is no longer there, say. */
    private val assignFailure: VaultError? = null,
) : VaultItems {
    var thumbnailCalls = 0
        private set
    val summaries = MutableStateFlow<List<VaultItemSummary>>(emptyList())
    val albums = MutableStateFlow<List<AlbumSummary>>(emptyList())
    val importedNames = mutableListOf<String>()
    var importCalls = 0
        private set
    var lastName: String? = null
        private set
    var lastMime: String? = null
        private set
    var observedFilter: ItemFilter? = null
        private set

    /** The destination of every import call, in order — so a batch's filing can be checked per file. */
    val importedAlbums = mutableListOf<ByteArray?>()

    /** The already-open source of the most recent call, or `null` when the caller handed none. */
    var handedFirst: RawSource? = null
        private set

    override fun observe(filter: ItemFilter): Flow<List<VaultItemSummary>> {
        observedFilter = filter
        return summaries
    }

    override fun observeAlbums(): Flow<List<AlbumSummary>> = albums

    /**
     * Every name a create was asked for, in order. A list rather than a counter and a `lastName`:
     * what has to be checkable is that a blank name never reached the vault at all, which a count
     * alone cannot say.
     */
    val createdNames = mutableListOf<String>()

    /** Every `(id, name)` a rename was asked for, so a blank name reaching the vault is catchable. */
    val renames = mutableListOf<Pair<List<Byte>, String>>()

    /** Every id a delete was asked for. */
    val deletedAlbums = mutableListOf<List<Byte>>()

    override fun createAlbum(name: String): Outcome<ByteArray> {
        // The real vault treats a blank name as a caller bug and throws. The fake keeps that
        // promise, so a use case that stopped validating is caught here rather than passing.
        require(name.isNotBlank()) { "album name must not be blank" }
        createdNames += name
        return createResult
    }

    override fun renameAlbum(id: ByteArray, name: String): Outcome<Unit> {
        // The same precondition the vault has, for the same reason as [createAlbum]'s.
        require(name.isNotBlank()) { "album name must not be blank" }
        renames += id.toList() to name
        return renameResult
    }

    override fun deleteAlbum(id: ByteArray): Outcome<Unit> {
        deletedAlbums += id.toList()
        return deleteResult
    }

    /**
     * Every filing call, as `(ids, destination)` — `null` being unfiled.
     *
     * Recorded rather than counted, because what the tests have to be able to say is *which* items went
     * *where*: a move that filed the wrong ids, or filed them into the wrong album, is the failure mode
     * a count cannot see.
     */
    val assignments = mutableListOf<Pair<List<List<Byte>>, List<Byte>?>>()

    override fun assignItems(ids: List<ByteArray>, albumId: ByteArray?): Outcome<AssignSummary> {
        assignments += ids.map { it.toList() } to albumId?.toList()
        assignFailure?.let { return Outcome.Err(it) }
        return Outcome.Ok(AssignSummary(requested = ids.size, assigned = assignedOf(ids.size)))
    }

    override fun import(
        name: String,
        mime: String,
        albumId: ByteArray?,
        onProgress: (ImportPhase, Long) -> Unit,
        isActive: () -> Boolean,
        first: RawSource?,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> {
        importCalls++
        importedAlbums += albumId
        handedFirst = first
        // The real vault owns a handed source and closes it on every path. The fake keeps that
        // promise, so a caller that leaks one is not covered for by the test double.
        first?.close()
        lastName = name
        lastMime = mime
        importedNames += name
        onImport(name)
        openSource() // the real implementation reads it; make sure the fake exercises the opener too
        // The real guard aborts a read once the predicate goes false; the fake checks once, which is
        // enough to prove the caller is wired to it.
        if (!isActive()) return Outcome.Err(VaultError.Io(RuntimeException("stopped")))
        if (phaseReports.isNotEmpty()) {
            phaseReports.forEach { (phase, bytes) -> onProgress(phase, bytes) }
        } else {
            // A plain byte list is an encrypt-phase read: that is the phase a payload read belongs
            // to unless the source spilled, which this shorthand never does.
            bytesPerImport.forEach { onProgress(ImportPhase.ENCRYPTING, it) }
        }
        return if (name in failFor) Outcome.Err(VaultError.Io(RuntimeException("unreadable"))) else importResult
    }

    override fun thumbnail(id: ByteArray): Outcome<ByteArray?> {
        thumbnailCalls++
        return thumbnails[id.toList()] ?: NO_THUMBNAIL
    }
}

/** Unknown ids answer "no preview", which is what the real seam does for an item without one. */
private val NO_THUMBNAIL: Outcome<ByteArray?> = Outcome.Ok(null)

/**
 * A listing that fails the way a broken catalog does: by **throwing inside the flow**, which is
 * `core:vault`'s documented contract for a listing that cannot be read.
 *
 * Shared by the gallery's and the album grid's tests: both screens have to turn that throw into a
 * reported error rather than an empty list, and the two would otherwise keep their own copy of a fake
 * whose one job is to get the failure shape right.
 */
internal class ExplodingVaultItems : VaultItems {
    override fun observe(filter: ItemFilter): Flow<List<VaultItemSummary>> =
        flow { throw IllegalStateException("catalog unreadable") }

    override fun observeAlbums(): Flow<List<AlbumSummary>> =
        flow { throw IllegalStateException("catalog unreadable") }

    override fun createAlbum(name: String): Outcome<ByteArray> = Outcome.Ok(byteArrayOf(1))

    override fun renameAlbum(id: ByteArray, name: String): Outcome<Unit> = Outcome.Ok(Unit)

    override fun deleteAlbum(id: ByteArray): Outcome<Unit> = Outcome.Ok(Unit)

    override fun assignItems(ids: List<ByteArray>, albumId: ByteArray?): Outcome<AssignSummary> =
        Outcome.Ok(AssignSummary(requested = ids.size, assigned = ids.size))

    override fun import(
        name: String,
        mime: String,
        albumId: ByteArray?,
        onProgress: (ImportPhase, Long) -> Unit,
        isActive: () -> Boolean,
        first: RawSource?,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> = Outcome.Ok(byteArrayOf(1))

    override fun thumbnail(id: ByteArray): Outcome<ByteArray?> = Outcome.Ok(null)
}

/** A seam whose import blows up rather than returning — the "should never happen" path. */
internal class ThrowingVaultItems : VaultItems {
    override fun observe(filter: ItemFilter): Flow<List<VaultItemSummary>> = MutableStateFlow(emptyList())

    override fun observeAlbums(): Flow<List<AlbumSummary>> = MutableStateFlow(emptyList())

    override fun createAlbum(name: String): Outcome<ByteArray> = throw IllegalStateException("create exploded")

    override fun renameAlbum(id: ByteArray, name: String): Outcome<Unit> =
        throw IllegalStateException("rename exploded")

    override fun deleteAlbum(id: ByteArray): Outcome<Unit> = throw IllegalStateException("delete exploded")

    override fun assignItems(ids: List<ByteArray>, albumId: ByteArray?): Outcome<AssignSummary> =
        throw IllegalStateException("filing exploded")

    override fun import(
        name: String,
        mime: String,
        albumId: ByteArray?,
        onProgress: (ImportPhase, Long) -> Unit,
        isActive: () -> Boolean,
        first: RawSource?,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> = throw IllegalStateException("import exploded")

    override fun thumbnail(id: ByteArray): Outcome<ByteArray?> = NO_THUMBNAIL
}

/** A seam that behaves like an import interrupted by a lock cancelling the scope. */
internal class CancellingVaultItems : VaultItems {
    override fun observe(filter: ItemFilter): Flow<List<VaultItemSummary>> = MutableStateFlow(emptyList())

    override fun observeAlbums(): Flow<List<AlbumSummary>> = MutableStateFlow(emptyList())

    override fun createAlbum(name: String): Outcome<ByteArray> =
        throw CancellationException("the session was torn down mid-create")

    override fun renameAlbum(id: ByteArray, name: String): Outcome<Unit> =
        throw CancellationException("the session was torn down mid-rename")

    override fun deleteAlbum(id: ByteArray): Outcome<Unit> =
        throw CancellationException("the session was torn down mid-delete")

    override fun assignItems(ids: List<ByteArray>, albumId: ByteArray?): Outcome<AssignSummary> =
        throw CancellationException("the session was torn down mid-move")

    override fun import(
        name: String,
        mime: String,
        albumId: ByteArray?,
        onProgress: (ImportPhase, Long) -> Unit,
        isActive: () -> Boolean,
        first: RawSource?,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> = throw CancellationException("the session was torn down mid-import")

    override fun thumbnail(id: ByteArray): Outcome<ByteArray?> = NO_THUMBNAIL
}

internal fun summary(
    id: ByteArray,
    meta: Outcome<ObjectMeta>,
    size: Long = 12L,
    createdAt: Long = 34L,
    kind: VaultItemKind = VaultItemKind.FILE,
): VaultItemSummary = VaultItemSummary(id = id, kind = kind, size = size, createdAt = createdAt, meta = meta)

internal fun readableMeta(name: String = "a.txt", mime: String = "text/plain"): Outcome<ObjectMeta> =
    Outcome.Ok(ObjectMeta(name, mime))

internal fun damagedMeta(): Outcome<ObjectMeta> = Outcome.Err(VaultError.Crypto.IntegrityFailure)
