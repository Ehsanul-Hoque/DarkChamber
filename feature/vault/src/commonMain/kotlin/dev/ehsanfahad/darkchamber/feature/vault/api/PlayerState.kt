package dev.ehsanfahad.darkchamber.feature.vault.api

import dev.ehsanfahad.darkchamber.core.model.VaultError

/*
 * What a player reports. Descriptions only — positions, flags, kinds — and a source rule keeps it that
 * way: this file may mention no player, surface or picture type, so the state cannot come to carry one.
 */

/** Where one player stands. Times are milliseconds. */
data class PlayerState(
    val positionMs: Long = 0L,
    /** `null` until the player knows it. */
    val durationMs: Long? = null,
    val playing: Boolean = false,
    /** Played to its end and stopped there. */
    val ended: Boolean = false,
    val tracks: PlayerTracks = PlayerTracks(),
    /** Why playback ended early, or `null` while it has not. */
    val failure: PlayerFailure? = null,
)

/** What the item holds, and whether this device can play it — the tracks found against those chosen. */
data class PlayerTracks(
    val video: TrackSupport = TrackSupport.Absent,
    val audio: TrackSupport = TrackSupport.Absent,
)

enum class TrackSupport {
    /** The item has no track of this kind, or none has been found yet. */
    Absent,

    /** There is one and this device plays it. */
    Playable,

    /** There is one and none of this device's decoders can play it. */
    Unplayable,
}

/**
 * Why playback ended early, by **where it came from** — the implementation records the reader's own
 * outcome, so a decoder's refusal is never mistaken for damage, nor damage for a format this device
 * does not play.
 */
sealed interface PlayerFailure {
    /** The vault refused a read, with its own error: a failed integrity check, a newer format, I/O. */
    data class Reader(
        val error: VaultError,
    ) : PlayerFailure

    /** No decoder on this device handles the item's format or profile, and no read failed behind it. */
    data object Decoder : PlayerFailure

    /** Anything else; it names no cause. */
    data object Other : PlayerFailure
}
