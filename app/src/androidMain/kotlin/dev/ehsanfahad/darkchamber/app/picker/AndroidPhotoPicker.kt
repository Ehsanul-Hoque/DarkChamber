package dev.ehsanfahad.darkchamber.app.picker

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import dev.ehsanfahad.darkchamber.app.system.SystemUiHandoff
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile

/**
 * Android's photo picker as the [PhotoPicker] seam, reaching the current `FragmentActivity` through
 * the same holder as the document picker and the biometric prompt.
 *
 * **No permission, by design.** `PickMultipleVisualMedia` hands back a read grant on exactly what the
 * user chose, so the app never asks for `READ_MEDIA_IMAGES` and never sees the rest of the library
 * — `config/permissions-allowlist.txt` is untouched by this picker (hard rule #7).
 *
 * The grant is not persistable: the bytes are read once, straight into a `.dchm` object, and a
 * standing permission on someone's photos afterwards would be access we do not need.
 */
class AndroidPhotoPicker(
    private val activity: () -> FragmentActivity?,
    private val handoff: SystemUiHandoff,
) : PhotoPicker {

    override suspend fun pickPhotos(): List<PickedFile> {
        val host = activity() ?: error("no activity to present the picker from")

        // Like SAF, the photo picker is a separate activity: launching it *stops* ours, and without
        // the handoff the on-background auto-lock tears the session down mid-pick and the result
        // comes back to nothing (the cycle-1 device finding that made this exemption exist). It
        // covers the round-trip only, and is released on every path.
        val uris = handoff.during {
            // An empty list on cancel, which is already the seam's "the user backed out".
            host.awaitActivityResult(
                keyPrefix = "darkchamber-photos",
                contract = ActivityResultContracts.PickMultipleVisualMedia(),
                input = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        }

        return uris.map { AndroidPickedFile(host.contentResolver, it) }
    }
}
