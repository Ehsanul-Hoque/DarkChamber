package dev.ehsanfahad.darkchamber.feature.vault.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import kotlinx.coroutines.flow.StateFlow

/**
 * The platform seam that plays a video or audio item for the viewer. The app module implements it per
 * platform — ExoPlayer on Android, AVPlayer on iOS — as it does the image decoder and the camera.
 *
 * The contract every implementation keeps:
 *
 * - **Reads only through [RangeReader].** The player asks for byte ranges; the reader decrypts only the
 *   chunks a range touches. Nothing decrypted is written to a file or a cache, the player's own disk
 *   cache included (hard rule #1).
 * - **Owns the reader it is given.** [create] hands it over; [VaultPlayer.release] closes it, and so does
 *   every failure that ends playback. The reader serves one caller, so the implementation serialises
 *   the player's reads into it.
 * - **Reports where a failure came from**, never from an error's text: see [PlayerFailure].
 * - **Shows nothing outside the app** — no media session, notification, lock-screen or external
 *   playback route.
 * - **Declines, never throws**, across this seam.
 */
interface VaultPlayerFactory {
    /** A player for one item, over [reader], which it now owns. [mime] is the item's stored type. */
    fun create(reader: RangeReader, mime: String): VaultPlayer
}

/** One item's player. Created paused at the start; nothing plays until [play]. */
interface VaultPlayer {
    val state: StateFlow<PlayerState>

    fun play()

    fun pause()

    /** Moves to [positionMs]; the caller keeps it within the item (see the playback rules). */
    fun seekTo(positionMs: Long)

    /** Runs at [speed]: 1 is normal. Pitch is kept where the platform keeps it by default. */
    fun setSpeed(speed: Float)

    /**
     * Sets the level the volume slide controls, from 0 to 1. Which volume that is belongs to the
     * platform: the phone's media volume on Android, the player's own on iOS, which gives apps no way
     * to set the phone's.
     */
    fun setVolume(level: Float)

    /** The item's picture, for a video page. An audio item draws nothing. */
    @Composable
    fun Picture(modifier: Modifier)

    /**
     * Stops, closes the reader and lets go of the platform player. Safe to call more than once.
     *
     * **The viewer calls this off the main thread**, so a lock is never held up by a decoder that
     * blocks while it shuts down; an implementation whose player is bound to a thread hands the work to
     * that thread itself. The viewer has stopped listening to [state] before it calls this, so whatever
     * the released player reports afterwards is ignored.
     */
    fun release()
}

