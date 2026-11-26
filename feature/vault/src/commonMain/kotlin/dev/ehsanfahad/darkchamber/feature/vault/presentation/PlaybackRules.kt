package dev.ehsanfahad.darkchamber.feature.vault.presentation

import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerState
import kotlin.math.roundToLong

/**
 * The player's seek, speed and end-of-media rules — every decision the controls and gestures make,
 * kept out of the page and the platform players so it can be tested. Times are milliseconds.
 */
internal object PlaybackRules {
    /** How far a double tap moves, back on the left half and forward on the right. */
    const val SKIP_MS = 10_000L

    /** The speeds the speed setting offers. 1× is where every viewer starts. */
    val SPEEDS: List<Float> = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

    const val NORMAL_SPEED = 1f

    /**
     * Where a skip of [byMs] from [positionMs] lands: never before the start, and never past the end
     * when the end is known. [durationMs] is null while the player does not know it yet.
     */
    fun skipped(
        positionMs: Long,
        byMs: Long,
        durationMs: Long?,
    ): Long {
        val landed = (positionMs + byMs).coerceAtLeast(0L)
        return if (durationMs == null) landed else landed.coerceAtMost(durationMs)
    }

    /** How far along the seek bar [positionMs] is, from 0 to 1; 0 while the length is not known. */
    fun seekFraction(
        positionMs: Long,
        durationMs: Long?,
    ): Float {
        if (durationMs == null || durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    /** The position a point [fraction] along the seek bar stands for. */
    fun seekPosition(
        fraction: Float,
        durationMs: Long,
    ): Long = (fraction.coerceIn(0f, 1f).toDouble() * durationMs).roundToLong()

    /** The speed the player runs at: a held half's speed while it is held, else the one that was set. */
    fun runningSpeed(
        setSpeed: Float,
        held: HeldSpeed?,
    ): Float = held?.speed ?: setSpeed

    /** Where a player that reported [state] stands: an ended item is ended, whatever else it says. */
    fun status(state: PlayerState): PlaybackStatus =
        when {
            state.ended -> PlaybackStatus.Ended
            state.playing -> PlaybackStatus.Playing
            else -> PlaybackStatus.Paused
        }

    /** What the play/pause control in the centre shows, and does, for [status]. */
    fun centreControl(status: PlaybackStatus): CentreControl =
        when (status) {
            PlaybackStatus.Playing -> CentreControl.Pause
            PlaybackStatus.Paused -> CentreControl.Play
            PlaybackStatus.Ended -> CentreControl.Replay
        }

    /** Only a playing item keeps the screen on; a paused or finished one lets it sleep. */
    fun keepsScreenOn(status: PlaybackStatus): Boolean = status == PlaybackStatus.Playing
}

/** A half held down: slower on the left, faster on the right, only while the finger stays. */
internal enum class HeldSpeed(
    val speed: Float,
) {
    Slow(0.5f),
    Fast(2f),
}

/** Where a page's playback stands. [Ended] is the last frame, stopped, with nothing looping. */
internal enum class PlaybackStatus { Playing, Paused, Ended }

/** The centre control: [Replay] starts a finished item again from the beginning. */
internal enum class CentreControl { Play, Pause, Replay }

/**
 * What the viewer remembers between its pages, for as long as it is open: each item's position, so
 * leaving a page and coming back resumes it, and the speed that was set, which carries from item to
 * item. Held in memory only — never stored — so closing the viewer or a lock, which discard the
 * viewer and this with it, forgets all of it.
 */
internal class PlaybackMemory {
    /** The speed the speed setting last chose; every item plays at it. */
    var speed: Float = PlaybackRules.NORMAL_SPEED
        private set

    /** Sets the speed, if [choice] is one the setting offers; anything else leaves it as it was. */
    fun chooseSpeed(choice: Float) {
        if (choice in PlaybackRules.SPEEDS) speed = choice
    }

    private val positions = HashMap<ItemKey, Long>()

    /**
     * Records where [id] stood as its page was left. [ended] means it had played to its end; a
     * position at the end counts as that too, so either way the item starts again from the beginning.
     */
    fun leave(
        id: ByteArray,
        positionMs: Long,
        durationMs: Long?,
        ended: Boolean,
    ) {
        val finished = ended || (durationMs != null && positionMs >= durationMs)
        positions[ItemKey(id)] = if (finished) 0L else positionMs.coerceAtLeast(0L)
    }

    /** Where [id] starts when its page is shown: where it was left, or the beginning. */
    fun startPosition(id: ByteArray): Long = positions[ItemKey(id)] ?: 0L

    /** An id as a map key: equal by its content, which a `ByteArray` on its own is not. */
    private class ItemKey(
        private val id: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = other is ItemKey && other.id.contentEquals(id)

        override fun hashCode(): Int = id.contentHashCode()
    }
}
