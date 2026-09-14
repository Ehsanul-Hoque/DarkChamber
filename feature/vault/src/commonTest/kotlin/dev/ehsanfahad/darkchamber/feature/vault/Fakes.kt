package dev.ehsanfahad.darkchamber.feature.vault

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.vault.ObjectMeta
import dev.ehsanfahad.darkchamber.core.vault.VaultItemSummary
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import dev.ehsanfahad.darkchamber.feature.vault.domain.VaultItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.Buffer
import kotlinx.io.RawSource
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
) : PickedFile {
    var closed = false
        private set
    var opens = 0
        private set

    override fun openSource(): RawSource {
        opens++
        return Buffer().apply { write(data) }
    }

    override fun close() {
        closed = true
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
) : VaultItems {
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

    override fun observe(albumId: ByteArray?): Flow<List<VaultItemSummary>> {
        observedAlbum = albumId
        return summaries
    }

    override fun import(
        name: String,
        mime: String,
        onProgress: (Long) -> Unit,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> {
        importCalls++
        lastName = name
        lastMime = mime
        importedNames += name
        openSource() // the real implementation reads it; make sure the fake exercises the opener too
        bytesPerImport.forEach(onProgress)
        return if (name in failFor) Outcome.Err(VaultError.Io(RuntimeException("unreadable"))) else importResult
    }
}

/** A seam whose import blows up rather than returning — the "should never happen" path. */
internal class ThrowingVaultItems : VaultItems {
    override fun observe(albumId: ByteArray?): Flow<List<VaultItemSummary>> = MutableStateFlow(emptyList())

    override fun import(
        name: String,
        mime: String,
        onProgress: (Long) -> Unit,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> = throw IllegalStateException("import exploded")
}

/** A seam that behaves like an import interrupted by a lock cancelling the scope. */
internal class CancellingVaultItems : VaultItems {
    override fun observe(albumId: ByteArray?): Flow<List<VaultItemSummary>> = MutableStateFlow(emptyList())

    override fun import(
        name: String,
        mime: String,
        onProgress: (Long) -> Unit,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> = throw CancellationException("the session was torn down mid-import")
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
