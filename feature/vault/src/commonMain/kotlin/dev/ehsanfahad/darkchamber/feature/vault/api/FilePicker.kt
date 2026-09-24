package dev.ehsanfahad.darkchamber.feature.vault.api

import kotlinx.io.RawSource

/**
 * The platform seam for choosing files to import — Android's Storage Access Framework, iOS's
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
     * Returns the files in the order the platform reported them, or an **empty list** when the user
     * cancels — a cancellation is an ordinary outcome, not an error, and must not surface as a
     * failure banner. Throws only if the picker itself cannot be shown.
     *
     * Every returned file holds a platform grant, so the caller must close **all** of them, not
     * only the ones it manages to import.
     */
    suspend fun pickFiles(): List<PickedFile>
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
     * The file's byte size **if the platform will say**, and `null` if it will not.
     *
     * Progress needs a denominator before the encrypt begins, and the codec only learns the true
     * size during its first pass — too late to draw anything with. The picker knows it earlier, so
     * it is carried here.
     *
     * `null` means unknown, never zero and never a number to guess at: a cloud provider routinely
     * declines to size a file it has not downloaded. A bar drawn against an invented total is a UI
     * that lies about progress (hard rule #6) — show an indeterminate indicator instead. It is a
     * hint for display only; nothing about the import depends on it being present or correct.
     */
    val size: Long?

    /**
     * Open a fresh, independent source over the file's bytes.
     *
     * Should be callable more than once — the `.dchm` codec reads its payload twice — but a
     * provider that refuses is handled: `VaultObjects.importObject` probes for re-openability and
     * spills a one-shot source into app-private scratch rather than failing the import.
     */
    fun openSource(): RawSource

    /**
     * Whether the platform will let us ask for this original to be deleted after it is imported.
     *
     * **`false` is the ordinary answer, not a fault.** Cloud providers routinely decline to
     * advertise deletion, items chosen from a photo library never support it, and on iOS it is
     * always `false` by design. Callers must treat the absence of the capability as the normal
     * shape of things: no offer at all, rather than a disabled control that owes the user an
     * explanation.
     *
     * Resolving this may cost a platform round-trip, so read it off the import dispatcher — never
     * while mapping a pick result on the main thread.
     *
     * Defaulted to `false` deliberately. Deleting someone's file is the one irreversible thing on
     * this interface, so an implementation that says nothing declines; the failure mode of
     * forgetting is a lost offer, never a claim we cannot honour (hard rule #6).
     */
    val deletable: Boolean get() = false

    /**
     * Ask the platform to delete the original, and report whether it actually went.
     *
     * `true` means the platform said it deleted the file. `false` means nothing was deleted —
     * [deletable] was `false`, or the provider refused, or it failed. It never throws: a refusal is
     * an outcome the caller shows, not an error that aborts anything, and by the time this is
     * called the bytes are already safely in the vault.
     *
     * **Valid after [close], which is the surprising part.** The import closes every picked file in
     * a `finally`, so every deletion is asked for on a closed file. That works because the grant
     * being released by [close] belongs to the platform host — the activity that ran the picker, the
     * security-scoped resource — and not to the stream; deleting needs the grant, not an open
     * handle. It is a widening of the contract rather than an accident of it, so implementations
     * must keep it true: a closed file that can still be deleted is worth stating out loud.
     *
     * Deleting is best-effort by nature. The system unlinks the file; flash wear-levelling may leave
     * recoverable traces outside our control, which is why the UI that calls this says what we asked
     * for rather than promising erasure (`docs/THREAT_MODEL.md`, hard rule #6).
     */
    fun deleteOriginal(): Boolean = false
}
