package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItemMeta
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerFailure
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerKind
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerTracks
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackNote
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackSupport
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
 * Which player an item gets: a stored video or audio type plays; anything else, and an item whose
 * details will not open — its type unknown — does not (`null`). Decided from the stored type alone,
 * before anything is read.
 */
internal fun mediaKind(meta: GalleryItemMeta): PlayerKind? {
    if (meta !is GalleryItemMeta.Readable) return null
    return when {
        meta.mime.startsWith("video/") -> PlayerKind.Video
        meta.mime.startsWith("audio/") -> PlayerKind.Audio
        else -> null
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

/**
 * The page for playback that ended early, decided by where the failure came from: a refused read is
 * judged as an open would be ([pageForOpenFailure]), so only the vault's own integrity check makes a
 * file [ViewerPage.Unverified]; a decoder that declines the format is [ViewerPage.CantPlay], never
 * damage; a stop or a lock is no page at all (`null`) — the player was let go, not failed.
 */
internal fun pageForPlayerFailure(failure: PlayerFailure): ViewerPage? =
    when (failure) {
        // Mid-play, the verified chunks before the refused one have already played.
        is PlayerFailure.Reader -> pageForOpenFailure(failure.error).let {
            if (it == ViewerPage.Unverified) ViewerPage.StoppedUnverified else it
        }
        PlayerFailure.Decoder -> ViewerPage.CantPlay
        PlayerFailure.Stopped -> null
        PlayerFailure.Other -> ViewerPage.CouldntOpen
    }

/**
 * The note for an item that plays in part: one kind of track this device cannot play while the other
 * plays. `null` when everything found plays, when there is nothing to play at all (that is
 * [nothingPlays]'s page), and before the tracks are known. A track the item simply does not have — a
 * silent clip, an audio file's picture — is not missing.
 */
internal fun trackNote(tracks: PlayerTracks): TrackNote? =
    when {
        tracks.video == TrackSupport.Unplayable && tracks.audio == TrackSupport.Playable -> TrackNote.NoPicture
        tracks.audio == TrackSupport.Unplayable && tracks.video == TrackSupport.Playable -> TrackNote.NoSound
        else -> null
    }

/** Every track found is one this device cannot play: the whole page is [ViewerPage.CantPlay]. */
internal fun nothingPlays(tracks: PlayerTracks): Boolean {
    val found = listOf(tracks.video, tracks.audio).filter { it != TrackSupport.Absent }
    return found.isNotEmpty() && found.none { it == TrackSupport.Playable }
}
