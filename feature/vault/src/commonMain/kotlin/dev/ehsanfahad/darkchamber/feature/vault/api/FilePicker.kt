package dev.ehsanfahad.darkchamber.feature.vault.api

import kotlinx.io.RawSource

/**
 * The platform seam for choosing a file to import — Android's Storage Access Framework, iOS's
 * document picker. The implementations live in the app module, which owns the platform host, the
 * same way the biometric authenticator's do; this module only states the contract.
 *
 * The picker grants **read** access to something the user explicitly chose. Nothing is written back
 * to the picked location, and the bytes go straight into a `.dchm` object.
 */
interface FilePicker {
    /**
     * Present the picker and suspend until the user chooses or cancels.
     *
     * Returns `null` when the user cancels — a cancellation is an ordinary outcome, not an error,
     * and must not surface as a failure banner. Throws only if the picker itself cannot be shown.
     */
    suspend fun pickFile(): PickedFile?
}

/**
 * A file the user picked, held open only as long as the import needs it.
 *
 * [close] releases the platform grant — the Android persistable URI permission, the iOS
 * security-scoped resource — so a picked file must always be closed, including when the import
 * fails. Callers use it in a `try`/`finally`.
 */
interface PickedFile : AutoCloseable {
    /** The display name the user would recognise. Stored encrypted; never logged (hard rule #4). */
    val name: String

    /** The MIME type reported by the platform, used to choose a viewer later. */
    val mime: String

    /**
     * Open a fresh, independent source over the file's bytes.
     *
     * Should be callable more than once — the `.dchm` codec reads its payload twice — but a
     * provider that refuses is handled: `VaultObjects.importObject` probes for re-openability and
     * spills a one-shot source into app-private scratch rather than failing the import.
     */
    fun openSource(): RawSource
}
