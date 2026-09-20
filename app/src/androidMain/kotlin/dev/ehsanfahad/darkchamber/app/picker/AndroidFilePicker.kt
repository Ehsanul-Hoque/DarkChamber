package dev.ehsanfahad.darkchamber.app.picker

import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import dev.ehsanfahad.darkchamber.app.system.SystemUiHandoff
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile

/**
 * Android's Storage Access Framework as the [FilePicker] seam. Mirrors how the biometric
 * authenticator reaches the current `FragmentActivity` — through a holder, never a static reference.
 *
 * The picker asks for a **read** grant on the files the user chose. The grant is deliberately *not*
 * made persistable: the bytes are read once, straight into a `.dchm` object, so holding a standing
 * permission on someone's file after the import would be access we do not need.
 */
class AndroidFilePicker(
    private val activity: () -> FragmentActivity?,
    private val handoff: SystemUiHandoff,
) : FilePicker {

    override suspend fun pickFiles(): List<PickedFile> {
        val host = activity() ?: error("no activity to present the picker from")

        // SAF is a separate activity, so launching it *stops* ours. Without this the on-background
        // auto-lock tears the session down mid-pick and the result arrives with nothing to receive
        // it. The exemption covers the round-trip only, and is released on every path.
        val uris = handoff.during {
            // OpenMultipleDocuments delivers an empty list on cancel, which is exactly the seam's
            // "the user backed out" — no separate null case to carry.
            host.awaitActivityResult(
                keyPrefix = "darkchamber-import",
                contract = ActivityResultContracts.OpenMultipleDocuments(),
                input = arrayOf(ANY_TYPE),
            )
        }

        return uris.map { AndroidPickedFile(host.contentResolver, it) }
    }

    private companion object {
        const val ANY_TYPE = "*/*"
    }
}
