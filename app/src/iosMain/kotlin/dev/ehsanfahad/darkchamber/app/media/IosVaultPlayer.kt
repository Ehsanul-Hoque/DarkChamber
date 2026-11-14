package dev.ehsanfahad.darkchamber.app.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPlayer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPlayerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.isPlayable
import platform.AVFoundation.mediaType
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.resourceLoader
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setAllowsExternalPlayback
import platform.AVFoundation.setDefaultRate
import platform.AVFoundation.setPreferredForwardBufferDuration
import platform.AVFoundation.setPreventsDisplaySleepDuringVideoPlayback
import platform.AVFoundation.setRate
import platform.AVFoundation.setVolume
import platform.AVFoundation.rate
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.tracks
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTimer
import platform.Foundation.NSUnderlyingErrorKey
import platform.QuartzCore.CATransaction
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlinx.cinterop.readValue
import kotlin.concurrent.AtomicInt

/** The iOS players: AVPlayer, reading an item only through its range reader. */
@OptIn(ExperimentalForeignApi::class)
internal class IosVaultPlayerFactory : VaultPlayerFactory {
    override fun create(reader: RangeReader, mime: String): VaultPlayer {
        // Media playback: sound plays with the ring switch on silent, as a video app's does. The app has
        // no background audio mode, so leaving it still stops the sound.
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        return IosVaultPlayer(reader, mime)
    }
}

/**
 * One item's AVPlayer, over a [ReaderLoader] that serves the asset's bytes from the item's reader on a
 * serial queue of its own.
 *
 * Built with no external playback (no AirPlay route) and a forward buffer of about 12 seconds. It never
 * touches the now-playing centre or remote commands, so nothing of the item reaches the lock screen.
 * AVPlayer pauses itself when a call takes the sound or headphones are unplugged, and does not resume
 * on its own afterwards. While it plays a video it keeps the screen awake, and lets it sleep otherwise.
 *
 * AVPlayer reports little without key-value observing, so the state is read four times a second on the
 * main thread. Releasing hands its work to the main queue and the loader's queue and returns at once.
 *
 * **Unverified on an iPhone**; exercised on the simulator, whose AVFoundation plays the same way.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosVaultPlayer(private val reader: RangeReader, mime: String) : VaultPlayer {
    private val queue = dispatch_queue_create("dev.ehsanfahad.darkchamber.player", null)
    private val loader = ReaderLoader(reader, assetTypeFor(mime).uti, queue)
    private val released = AtomicInt(0)

    private val asset = AVURLAsset(uRL = assetUrl(SCHEME, assetTypeFor(mime))!!, options = null).also {
        it.resourceLoader().setDelegate(loader, queue)
    }
    private val item = AVPlayerItem(asset = asset).also {
        it.setPreferredForwardBufferDuration(IosPlayerMapping.FORWARD_BUFFER_SECONDS)
    }
    private val player = AVPlayer(playerItem = item).also {
        it.setAllowsExternalPlayback(false)
        it.setPreventsDisplaySleepDuringVideoPlayback(true)
    }
    private val layer = AVPlayerLayer.playerLayerWithPlayer(player).also { it.videoGravity = AVLayerVideoGravityResizeAspect }

    private val _state = MutableStateFlow(PlayerState(volume = 1f))
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var tracksRead = false
    private val ended: NSObjectProtocol = NSNotificationCenter.defaultCenter.addObserverForName(
        name = AVPlayerItemDidPlayToEndTimeNotification,
        `object` = item,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> if (released.value == 0) _state.update { it.copy(ended = true, playing = false) } }

    private val poll: NSTimer = NSTimer.scheduledTimerWithTimeInterval(POLL_SECONDS, repeats = true) { _ -> read() }

    // Main thread: what the player and its item say now.
    private fun read() {
        if (released.value != 0) return
        if (item.status == AVPlayerItemStatusFailed) {
            val failure = IosPlayerMapping.failureOf(chainOf(item.error), loader.failure)
            _state.update { it.copy(playing = false, failure = failure) }
            return
        }
        if (item.status == AVPlayerItemStatusReadyToPlay && !tracksRead) {
            tracksRead = true
            val found = asset.tracks().filterIsInstance<AVAssetTrack>().map {
                IosPlayerMapping.FoundTrack(
                    video = it.mediaType == AVMediaTypeVideo,
                    audio = it.mediaType == AVMediaTypeAudio,
                    playable = it.isPlayable(),
                )
            }
            _state.update { it.copy(tracks = IosPlayerMapping.tracksOf(found)) }
        }
        val position = CMTimeGetSeconds(player.currentTime())
        val duration = CMTimeGetSeconds(item.duration)
        _state.update {
            it.copy(
                positionMs = if (position.isNaN()) it.positionMs else (position * 1_000).toLong(),
                durationMs = if (duration.isNaN() || duration.isInfinite()) it.durationMs else (duration * 1_000).toLong(),
                playing = player.timeControlStatus == AVPlayerTimeControlStatusPlaying,
            )
        }
    }

    override fun play() = onMain { player.play() }

    override fun pause() = onMain { player.pause() }

    override fun seekTo(positionMs: Long) = onMain {
        player.seekToTime(CMTimeMakeWithSeconds(positionMs / 1_000.0, 1_000))
        _state.update { it.copy(positionMs = positionMs, ended = false) }
    }

    override fun setSpeed(speed: Float) = onMain {
        player.setDefaultRate(speed)
        if (player.rate != 0f) player.setRate(speed)
    }

    /** iOS gives apps no way to set the phone's volume: the slide sets this player's own. */
    override fun setVolume(level: Float) = onMain {
        val clamped = level.coerceIn(0f, 1f)
        player.setVolume(clamped)
        _state.update { it.copy(volume = clamped) }
    }

    @Composable
    override fun Picture(modifier: Modifier) {
        UIKitView(factory = { LayerHost(layer) }, modifier = modifier)
    }

    override fun release() {
        if (!released.compareAndSet(0, 1)) return
        dispatch_async(dispatch_get_main_queue()) {
            poll.invalidate()
            NSNotificationCenter.defaultCenter.removeObserver(ended)
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            layer.player = null
        }
        // On the loader's own queue, so no request it is serving runs into a closed reader.
        dispatch_async(queue) {
            loader.close()
            reader.close()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (released.value != 0) return
        dispatch_async(dispatch_get_main_queue()) { if (released.value == 0) block() }
    }

    private companion object {
        const val SCHEME = "darkchamber-player"
        const val POLL_SECONDS = 0.25
    }
}

/** An error and the errors under it, outermost first. */
internal fun chainOf(error: NSError?): List<IosPlayerMapping.ErrorLink> {
    val links = mutableListOf<IosPlayerMapping.ErrorLink>()
    var at = error
    while (at != null && links.size < MAX_CHAIN) {
        links += IosPlayerMapping.ErrorLink(at.domain, at.code)
        at = at.userInfo[NSUnderlyingErrorKey] as? NSError
    }
    return links
}

private const val MAX_CHAIN = 8

/** Keeps the player's layer the size of its view. */
@OptIn(ExperimentalForeignApi::class)
private class LayerHost(private val picture: AVPlayerLayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        layer.addSublayer(picture)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        picture.frame = bounds
        CATransaction.commit()
    }
}
