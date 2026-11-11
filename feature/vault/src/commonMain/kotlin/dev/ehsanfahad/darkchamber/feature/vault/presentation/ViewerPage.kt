package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage

/** What to do with an item before anything is read. */
internal sealed interface OpenPlan {
    data object Decode : OpenPlan

    data class Show(val page: ViewerPage) : OpenPlan
}

/**
 * Before anything is read: decode it, or show a page that needs no read at all.
 *
 * - A kind with no viewer yet (video, audio, PDF, anything else) shows its badge, and reads nothing.
 * - Anything larger than [cap] is not read into memory: [size] is a plaintext column, so this costs
 *   no decrypt, and an image too big to hold must not take the heap a passphrase unlock also needs.
 * - An item whose details will not open has no known type, but its object has its own key in the
 *   row and may open fine — so it is tried, within the cap, and the decoder decides from the bytes.
 * - A negative size is a row nothing here wrote: nothing is read, and the page names no cause.
 */
internal fun openPlan(meta: GalleryItemMeta, size: Long, cap: Long): OpenPlan {
    if (meta is GalleryItemMeta.Readable && !meta.mime.startsWith("image/")) {
        return OpenPlan.Show(ViewerPage.NotViewable(GalleryFormat.typeBadge(meta.mime)))
    }
    return when {
        size < 0 -> OpenPlan.Show(ViewerPage.CouldntOpen)
        size > cap -> OpenPlan.Show(ViewerPage.TooLarge)
        else -> OpenPlan.Decode
    }
}

/**
 * The page for an object that would not open. Only a failed integrity check (or a container that is
 * not one) is [ViewerPage.Unverified]; a newer format asks for an update; anything else names no
 * cause. A lock is none of these — the session refuses by throwing, and the screen is gone.
 */
internal fun pageForOpenFailure(error: VaultError): ViewerPage =
    when (error) {
        VaultError.Crypto.IntegrityFailure, VaultError.Crypto.MalformedContainer -> ViewerPage.Unverified
        VaultError.Crypto.UnsupportedFormatVersion -> ViewerPage.NeedsUpdate
        else -> ViewerPage.CouldntOpen
    }
