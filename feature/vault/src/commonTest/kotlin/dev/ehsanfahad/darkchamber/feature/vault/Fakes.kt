package dev.ehsanfahad.darkchamber.feature.vault

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.vault.ImportPhase
import dev.ehsanfahad.darkchamber.core.vault.ObjectMeta
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import dev.ehsanfahad.darkchamber.feature.vault.domain.VaultItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.concurrent.Volatile
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
) : VaultItems {
    var thumbnailCalls = 0
        private set
    val summaries = MutableStateFlow<List<VaultItemSummary>>(emptyList())
    val importedNames = mutableListOf<String>()
    var importCalls = 0
        private set
    var lastName: String? = null
        private set
    var lastMime: String? = null
        private set
    var observedAlbum: ByteArray? = null
        private set

    /** The already-open source of the most recent call, or `null` when the caller handed none. */
    var handedFirst: RawSource? = null
        private set

    override fun observe(albumId: ByteArray?): Flow<List<VaultItemSummary>> {
        observedAlbum = albumId
        return summaries
    }

    override fun import(
        name: String,
        mime: String,
        onProgress: (ImportPhase, Long) -> Unit,
        isActive: () -> Boolean,
        first: RawSource?,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> {
        importCalls++
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

/** A seam whose import blows up rather than returning — the "should never happen" path. */
internal class ThrowingVaultItems : VaultItems {
    override fun observe(albumId: ByteArray?): Flow<List<VaultItemSummary>> = MutableStateFlow(emptyList())

    override fun import(
        name: String,
        mime: String,
        onProgress: (ImportPhase, Long) -> Unit,
        isActive: () -> Boolean,
        first: RawSource?,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> = throw IllegalStateException("import exploded")

    override fun thumbnail(id: ByteArray): Outcome<ByteArray?> = NO_THUMBNAIL
}

/** A seam that behaves like an import interrupted by a lock cancelling the scope. */
internal class CancellingVaultItems : VaultItems {
    override fun observe(albumId: ByteArray?): Flow<List<VaultItemSummary>> = MutableStateFlow(emptyList())

    override fun import(
        name: String,
        mime: String,
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
