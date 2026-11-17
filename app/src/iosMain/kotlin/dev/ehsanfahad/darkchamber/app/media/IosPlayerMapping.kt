package dev.ehsanfahad.darkchamber.app.media

import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerFailure
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerTracks
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackSupport
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVErrorFormatUnsupported
import platform.AVFoundation.AVFoundationErrorDomain

/**
 * The iOS player's decisions, kept apart so the simulator can test them: where a failure came from and
 * what an asset's tracks amount to.
 */
internal object IosPlayerMapping {
    /**
     * How far ahead the player buffers, in seconds. Everything it holds is decrypted media the app cannot
     * zero, so it is held to what smooth playback needs.
     */
    const val FORWARD_BUFFER_SECONDS = 12.0

    /**
     * AVFoundation's codes for a format, or a decoder, this device does not have. Not
     * `AVErrorDecoderTemporarilyUnavailable`: a decoder busy for now is not a device that cannot play
     * the file, so it names no cause.
     */
    val DECODER_CODES: Set<Long> = setOf(
        AVErrorFileFormatNotRecognized,
        AVErrorDecoderNotFound,
        AVErrorFormatUnsupported,
    )

    /** One error in an error's chain: its domain and code. */
    data class ErrorLink(val domain: String?, val code: Long)

    /**
     * Where a stop came from. A read the vault refused wins, whatever AVFoundation made of it; a format or
     * decoder this device lacks — anywhere in the error's chain, since AVFoundation wraps its causes — is
     * the decoder's; anything else names no cause.
     */
    fun failureOf(chain: List<ErrorLink>, readerFailure: VaultError?): PlayerFailure =
        when {
            readerFailure != null -> PlayerFailure.Reader(readerFailure)
            chain.any { it.domain == AVFoundationErrorDomain && it.code in DECODER_CODES } -> PlayerFailure.Decoder
            else -> PlayerFailure.Other
        }

    /** One track AVFoundation found: a picture or not, and whether this device plays it. */
    data class FoundTrack(val video: Boolean, val audio: Boolean, val playable: Boolean)

    /** The asset's picture and sound, as on Android: none absent, any playable playable, else unplayable. */
    fun tracksOf(found: List<FoundTrack>): PlayerTracks =
        PlayerTracks(video = supportOf(found.filter { it.video }), audio = supportOf(found.filter { it.audio }))

    private fun supportOf(ofKind: List<FoundTrack>): TrackSupport =
        when {
            ofKind.isEmpty() -> TrackSupport.Absent
            ofKind.any { it.playable } -> TrackSupport.Playable
            else -> TrackSupport.Unplayable
        }
}
