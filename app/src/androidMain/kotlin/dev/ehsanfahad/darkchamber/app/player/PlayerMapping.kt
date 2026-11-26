package dev.ehsanfahad.darkchamber.app.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerFailure
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerTracks
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackSupport

/**
 * The decisions the Android player makes, kept out of it so they can be tested without a device: where
 * a failure came from, what the item's tracks amount to, how far ahead it may read, when the system
 * taking the sound away pauses it, and what a volume level is in the phone's own steps.
 */
internal object PlayerMapping {
    /**
     * How much decoded-ahead media the player may hold: about 10 to 15 seconds. Everything it holds is
     * decrypted media the app cannot zero, so the default — up to 50 seconds, tens of megabytes of 4K —
     * is cut to what smooth playback needs.
     */
    const val MIN_BUFFER_MS = 10_000
    const val MAX_BUFFER_MS = 15_000

    /** Enough to start, and to restart after a stall, without waiting for the whole buffer. */
    const val BUFFER_FOR_PLAYBACK_MS = 1_000
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000

    /**
     * The player's error codes that mean this device's decoders, or its file readers, cannot handle the
     * item's format — not that anything failed to read.
     */
    val DECODER_CODES: Set<Int> =
        setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )

    /**
     * Where a stop came from. A read the vault refused is the vault's own verdict and wins over
     * whatever the player made of it; a format this device cannot decode, with no refused read behind
     * it, is the decoder's; anything else names no cause.
     */
    fun failureOf(
        errorCode: Int,
        readerFailure: VaultError?,
    ): PlayerFailure =
        when {
            readerFailure != null -> PlayerFailure.Reader(readerFailure)
            errorCode in DECODER_CODES -> PlayerFailure.Decoder
            else -> PlayerFailure.Other
        }

    /** One group of tracks the player found: its kind ([C.TRACK_TYPE_VIDEO] …) and whether any plays. */
    data class FoundTrack(
        val type: Int,
        val supported: Boolean,
    )

    /**
     * The item's picture and sound: none found is absent; any one this device plays makes it playable;
     * found but none playable is unplayable. Other kinds (subtitles, metadata) are not counted.
     */
    fun tracksOf(found: List<FoundTrack>): PlayerTracks =
        PlayerTracks(video = supportOf(found, C.TRACK_TYPE_VIDEO), audio = supportOf(found, C.TRACK_TYPE_AUDIO))

    private fun supportOf(
        found: List<FoundTrack>,
        type: Int,
    ): TrackSupport {
        val ofType = found.filter { it.type == type }
        return when {
            ofType.isEmpty() -> TrackSupport.Absent
            ofType.any { it.supported } -> TrackSupport.Playable
            else -> TrackSupport.Unplayable
        }
    }

    /**
     * Whether playback is paused for good when the player stops it for this reason. Another app taking
     * the sound for a while — a call — pauses it, so the end of the call does not start it again.
     */
    fun pausesFor(suppressionReason: Int): Boolean =
        suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS

    /** A level from 0 to 1 in the phone's media-volume steps, rounded, never outside them. */
    fun volumeStep(
        level: Float,
        maxStep: Int,
    ): Int = Math.round(level.coerceIn(0f, 1f) * maxStep)

    /** A step of the phone's media volume as a level from 0 to 1. */
    fun volumeLevel(
        step: Int,
        maxStep: Int,
    ): Float = if (maxStep <= 0) 0f else step.toFloat() / maxStep
}
