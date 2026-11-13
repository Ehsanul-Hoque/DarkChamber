package dev.ehsanfahad.darkchamber.feature.vault

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPlayer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPlayerFactory
import kotlinx.coroutines.flow.MutableStateFlow

/** A reader that serves nothing and counts its closes — what "the reader was closed" looks like. */
internal class FakeRangeReader(override val length: Long = 1_000L) : RangeReader {
    var closes = 0
        private set

    override fun readAt(position: Long, into: ByteArray, offset: Int, size: Int): Outcome<Int> =
        Outcome.Ok(RangeReader.END)

    override fun close() {
        closes++
    }
}

/**
 * A player that records what it was told, in order, and reports whatever state the test sets. Like a
 * real one, it closes the reader it owns when released.
 */
internal class FakePlayer(val reader: RangeReader, val mime: String) : VaultPlayer {
    override val state = MutableStateFlow(PlayerState())

    /** Every call, in order: "play", "pause", "seek:42000", "speed:1.5", "volume:0.5", "release". */
    val calls = mutableListOf<String>()

    val released: Boolean get() = "release" in calls

    override fun play() {
        calls += "play"
    }

    override fun pause() {
        calls += "pause"
    }

    override fun seekTo(positionMs: Long) {
        calls += "seek:$positionMs"
    }

    override fun setSpeed(speed: Float) {
        calls += "speed:$speed"
    }

    override fun setVolume(level: Float) {
        calls += "volume:$level"
    }

    @Composable
    override fun Picture(modifier: Modifier) = Unit

    override fun release() {
        calls += "release"
        reader.close()
    }
}

/** Makes [FakePlayer]s and keeps every one, in the order made. */
internal class FakePlayerFactory : VaultPlayerFactory {
    val made = mutableListOf<FakePlayer>()

    override fun create(reader: RangeReader, mime: String): VaultPlayer = FakePlayer(reader, mime).also { made += it }
}
