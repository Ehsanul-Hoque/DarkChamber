package dev.ehsanfahad.darkchamber.app.picker

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import dev.ehsanfahad.darkchamber.app.system.SystemUiHandoff
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PickedFile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.RawSource
import kotlinx.io.asSource
import kotlin.coroutines.resume

/**
 * Android's Storage Access Framework as the [FilePicker] seam. Mirrors how the biometric
 * authenticator reaches the current `FragmentActivity` — through a holder, never a static reference.
 *
 * The picker asks for a **read** grant on one file the user chose. The grant is deliberately *not*
 * made persistable: the bytes are read once, straight into a `.dchm` object, so holding a standing
 * permission on someone's file after the import would be access we do not need.
 */
class AndroidFilePicker(
    private val activity: () -> FragmentActivity?,
    private val handoff: SystemUiHandoff,
) : FilePicker {

    override suspend fun pickFile(): PickedFile? {
        val host = activity() ?: error("no activity to present the picker from")

        // SAF is a separate activity, so launching it *stops* ours. Without this the on-background
        // auto-lock tears the session down mid-pick and the result arrives with nothing to receive
        // it. The exemption covers the round-trip only, and is released on every path.
        val uri = handoff.during { awaitPickedUri(host) } ?: return null // the user backed out

        return AndroidPickedFile(host.contentResolver, uri)
    }

    private suspend fun awaitPickedUri(host: FragmentActivity): Uri? =
        suspendCancellableCoroutine<Uri?> { continuation ->
            // Registered against the registry directly rather than via registerForActivityResult:
            // the latter must be called before the activity starts, and we are launching from a
            // live screen. The launcher is unregistered on every path, including cancellation.
            var launcher: ActivityResultLauncher<Array<String>>? = null
            launcher = host.activityResultRegistry.register(
                // A monotonic counter, not a hash: `register` throws on a key that is already in
                // use, and hashes can repeat. The component's guard makes overlapping picks
                // unlikely, but a crash is a poor way to find out it was only unlikely.
                "darkchamber-import-${nextRequestKey()}",
                ActivityResultContracts.OpenDocument(),
            ) { picked: Uri? ->
                launcher?.unregister()
                continuation.resume(picked)
            }
            continuation.invokeOnCancellation { launcher.unregister() }
            launcher.launch(arrayOf(ANY_TYPE))
        }

    private companion object {
        const val ANY_TYPE = "*/*"

        // Registry keys must be unique among *live* registrations. Requests only ever come from the
        // main thread (the picker is presented from a screen), so a plain counter is enough.
        private var requestCounter = 0L

        fun nextRequestKey(): Long = requestCounter++
    }
}

/**
 * A file the user picked, read through the content resolver. Each [openSource] opens a fresh stream,
 * which is what the `.dchm` codec's two passes need; a provider that refuses the second open is
 * handled upstream by the import's spill path.
 */
private class AndroidPickedFile(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : PickedFile {

    override val name: String = resolver.queryDisplayName(uri) ?: uri.lastPathSegment ?: FALLBACK_NAME

    override val mime: String = resolver.getType(uri) ?: FALLBACK_MIME

    override fun openSource(): RawSource =
        resolver.openInputStream(uri)?.asSource()
            ?: error("the provider returned no stream for the picked file")

    /**
     * Nothing to release: the read grant is not persistable, so it expires with the activity that
     * requested it. Kept for the contract — the iOS side has a real resource to hand back.
     */
    override fun close() = Unit

    private companion object {
        const val FALLBACK_NAME = "file"
        const val FALLBACK_MIME = "application/octet-stream"
    }
}

private fun ContentResolver.queryDisplayName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }
