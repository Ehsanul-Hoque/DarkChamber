package dev.ehsanfahad.darkchamber.feature.camera.data

import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.AlbumSummary
import dev.ehsanfahad.darkchamber.core.vault.ImportPhase
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.core.vault.WhenAlbumMissing
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureVault
import kotlinx.coroutines.flow.Flow
import kotlinx.io.RawSource

/** [CaptureVault] over the session's [VaultObjects] — a pass-through, so it holds no rule of its own. */
internal class VaultObjectsCaptures(private val objects: VaultObjects) : CaptureVault {
    override fun import(
        kind: Int,
        name: String,
        mime: String,
        albumId: ByteArray?,
        isActive: () -> Boolean,
        onProgress: (ImportPhase, Long) -> Unit,
        whenAlbumMissing: WhenAlbumMissing,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> =
        objects.importObject(
            kind = kind,
            name = name,
            mime = mime,
            albumId = albumId,
            onProgress = onProgress,
            isActive = isActive,
            whenAlbumMissing = whenAlbumMissing,
            openSource = openSource,
        )

    override fun observeAlbums(): Flow<List<AlbumSummary>> = objects.observeAlbums()

    override fun observeItemCount(filter: ItemFilter): Flow<Long> = objects.observeItemCount(filter)
}
