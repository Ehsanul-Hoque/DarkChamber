package dev.ehsanfahad.darkchamber.feature.camera.data

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.camera.domain.CaptureVault
import kotlinx.io.RawSource

/** [CaptureVault] over the session's [VaultObjects] — a pass-through, so it holds no rule of its own. */
internal class VaultObjectsCaptures(private val objects: VaultObjects) : CaptureVault {
    override fun import(
        kind: Int,
        name: String,
        mime: String,
        albumId: ByteArray?,
        isActive: () -> Boolean,
        openSource: () -> RawSource,
    ): Outcome<ByteArray> =
        objects.importObject(
            kind = kind,
            name = name,
            mime = mime,
            albumId = albumId,
            isActive = isActive,
            openSource = openSource,
        )
}
