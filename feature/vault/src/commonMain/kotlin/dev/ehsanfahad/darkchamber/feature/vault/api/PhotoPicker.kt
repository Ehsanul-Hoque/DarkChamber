package dev.ehsanfahad.darkchamber.feature.vault.api

/**
 * The platform seam for choosing photos and videos from the system library — Android's
 * `PickMultipleVisualMedia`, iOS's `PHPickerViewController`. Like [FilePicker], the implementations
 * live in the app module and this one only states the contract.
 *
 * **Why a second seam rather than a mode on [FilePicker].** They are different platform APIs with
 * different behaviour underneath, and the differences are exactly the kind that leak:
 *
 * - a media picker shows a **photo grid**, a document picker shows a **file browser** — the same
 *   affordance for both would be a chooser sheet nobody asked for;
 * - on Android the media picker runs as a separate activity and so **stops ours** — it must be held
 *   inside the session's picker handoff, whereas iOS presents its picker **in-app** and needs no
 *   such thing. One interface with one implementation per platform would have to carry that
 *   asymmetry as a flag;
 * - neither picker needs a runtime permission, but they earn that differently, and collapsing them
 *   would invite a future change to reach for one (`config/permissions-allowlist.txt`, hard rule
 *   #7).
 *
 * What it deliberately **does** share is [PickedFile]: a photo and a document are the same thing
 * once chosen, so both pickers feed one import batch and nothing downstream knows which was used.
 */
interface PhotoPicker {
    /**
     * Present the photo library and suspend until the user chooses or cancels.
     *
     * Returns the chosen items in the platform's order, or an **empty list** when the user backs
     * out — cancelling is an ordinary outcome, not a failure. Throws only if the picker itself
     * cannot be shown.
     *
     * Every returned item holds a platform grant, so the caller must close **all** of them, not
     * only the ones it manages to import.
     */
    suspend fun pickPhotos(): List<PickedFile>
}
