package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.feature.camera.api.CapturedPhoto

/**
 * Save one captured photo into the vault, from memory: write its orientation into the JPEG, encrypt
 * it into the vault filed in the destination album, and clear the plaintext this code holds.
 *
 * **Nothing is written to disk on the way.** The payload reaches the import as a re-openable source
 * over the array itself, so the import streams it straight into its encrypted container and never
 * needs its scratch copy.
 *
 * **What is cleared, exactly.** Every array this code holds — the capture's own and, when writing
 * the orientation needed a longer one, that one too — is zeroed on every path: a saved photo, a
 * failed import, an import abandoned by a lock. That is the whole of the claim. The platform's
 * capture buffers, and anything a decoder copies while making the thumbnail, are outside this
 * code's reach.
 *
 * The name is a timestamp ([nameFor] of [clock]): it sorts sensibly by name, needs no counter, and
 * two photos in the same second sharing one is fine — the id is identity, the name is a label. It is
 * stored encrypted like every other name and never logged.
 */
internal class SavePhoto(
    private val vault: CaptureVault,
    private val clock: () -> Long,
    private val nameFor: (epochMillis: Long) -> String = ::photoName,
) {
    operator fun invoke(photo: CapturedPhoto, albumId: ByteArray?, isActive: () -> Boolean): Outcome<ByteArray> {
        val captured = photo.jpeg
        var oriented = captured
        try {
            oriented = withOrientation(captured, photo.rotationDegrees)
            return vault.import(
                kind = VaultItemKind.FILE.code,
                name = nameFor(clock()),
                mime = JPEG_MIME,
                albumId = albumId,
                isActive = isActive,
                openSource = reopenableSource(oriented),
            )
        } finally {
            oriented.fill(0)
            if (oriented !== captured) captured.fill(0)
        }
    }

    private companion object {
        const val JPEG_MIME = "image/jpeg"
    }
}
