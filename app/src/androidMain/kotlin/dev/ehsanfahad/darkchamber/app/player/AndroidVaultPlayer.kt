package dev.ehsanfahad.darkchamber.app.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import dev.ehsanfahad.darkchamber.core.logging.logMeasurement
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerState
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPlayer
import dev.ehsanfahad.darkchamber.feature.vault.api.VaultPlayerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/** The Android players: ExoPlayer, reading an item only through its range reader. */
@UnstableApi
internal class AndroidVaultPlayerFactory(private val context: Context) : VaultPlayerFactory {
    init {
        // Media3's own log lines can quote a track's format, label included — text from inside the
        // file (hard rule 4). It logs nothing at all.
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
    }

    override fun create(reader: RangeReader, mime: String): VaultPlayer =
        AndroidVaultPlayer(context.applicationContext, SharedRangeReader(reader), mime)
}

/**
 * One item's ExoPlayer.
 *
 * **Its own thread.** The player lives on a thread of its own and every call is handed to it, so the
 * main thread never waits on it: not to play or pause, not when the picture goes away, and not when it
 * is let go — a decoder that takes seconds to shut down holds up only this thread, never a lock.
 *
 * **What it is built with, and without.** Only the item's own data source ([SharedRangeReader]); no
 * cache, no media session, no notification, no wake lock and no platform playback reporting. It reads
 * ahead 10–15 seconds at most ([PlayerMapping]). Tracks this device cannot decode are left out rather
 * than tried, so a clip whose picture will not decode still plays its sound.
 *
 * **The picture** goes to a TextureView, which is drawn as part of the app's window: the window's
 * screenshot protection covers it, and it moves with the pager like any other view.
 */
@UnstableApi
internal class AndroidVaultPlayer(
    private val context: Context,
    private val shared: SharedRangeReader,
    private val mime: String,
) : VaultPlayer {
    private val thread = HandlerThread("vault-player").apply { start() }
    private val handler = Handler(thread.looper)
    private val released = AtomicBoolean(false)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(PlayerState(volume = currentVolume()))
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Width over height of the picture as it should be shown, once known. */
    private val aspect = MutableStateFlow<Float?>(null)

    // Touched only on [thread].
    private var player: ExoPlayer? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (released.get()) return
            val exo = player ?: return
            _state.update {
                it.copy(
                    ended = playbackState == Player.STATE_ENDED,
                    durationMs = exo.duration.takeIf { d -> d != C.TIME_UNSET },
                    positionMs = exo.currentPosition,
                )
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (released.get()) return
            _state.update { it.copy(playing = isPlaying) }
            handler.removeCallbacks(poll) // one poll at a time
            if (isPlaying) handler.post(poll)
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (released.get()) return
            val found = tracks.groups.map { PlayerMapping.FoundTrack(it.type, it.isSupported(false)) }
            _state.update { it.copy(tracks = PlayerMapping.tracksOf(found)) }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (released.get()) return
            _state.update {
                it.copy(playing = false, failure = PlayerMapping.failureOf(error.errorCode, shared.failure))
            }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            // A call takes the sound: stay paused once it is over (the player would carry on itself).
            if (PlayerMapping.pausesFor(playbackSuppressionReason)) player?.pause()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width == 0 || videoSize.height == 0) return
            val shown = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            val turned = videoSize.unappliedRotationDegrees % 180 != 0
            aspect.value = if (turned) 1f / shown else shown
        }
    }

    /** While playing, the position is read four times a second; the player does not report it. */
    private val poll: Runnable = object : Runnable {
        override fun run() {
            val exo = player ?: return
            if (released.get() || !exo.isPlaying) return
            _state.update { it.copy(positionMs = exo.currentPosition) }
            handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        onPlayerThread {
            val exo = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                .setLooper(thread.looper)
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            PlayerMapping.MIN_BUFFER_MS,
                            PlayerMapping.MAX_BUFFER_MS,
                            PlayerMapping.BUFFER_FOR_PLAYBACK_MS,
                            PlayerMapping.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                        )
                        .build(),
                )
                .setTrackSelector(
                    DefaultTrackSelector(context).apply {
                        parameters = buildUponParameters()
                            .setExceedRendererCapabilitiesIfNecessary(false)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    },
                )
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    /* handleAudioFocus = */ true,
                )
                .setHandleAudioBecomingNoisy(true)
                .setUsePlatformDiagnostics(false)
                .build()
            exo.addListener(listener)
            exo.setMediaSource(
                ProgressiveMediaSource.Factory(shared.factory(), DefaultExtractorsFactory())
                    // A read the vault refused is its verdict; trying again would only decrypt the
                    // same chunk to the same answer and hold the page back.
                    .setLoadErrorHandlingPolicy(VaultLoadErrorPolicy)
                    .createMediaSource(MediaItem.Builder().setUri(ITEM_URI).setMimeType(mime).build()),
            )
            exo.prepare()
            player = exo
        }
    }

    override fun play() = onPlayerThread { player?.play() }

    override fun pause() = onPlayerThread { player?.pause() }

    override fun seekTo(positionMs: Long) = onPlayerThread {
        val exo = player ?: return@onPlayerThread
        exo.seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs, ended = false) }
    }

    override fun setSpeed(speed: Float) = onPlayerThread { player?.setPlaybackSpeed(speed) }

    override fun setVolume(level: Float) {
        if (released.get()) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, PlayerMapping.volumeStep(level, max), 0) }
        _state.update { it.copy(volume = currentVolume()) }
    }

    @Composable
    override fun Picture(modifier: Modifier) {
        val shape by aspect.collectAsState()
        val playing by state.collectAsState()
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { viewContext -> TextureView(viewContext).apply { surfaceTextureListener = surfaces } },
                update = { view -> view.keepScreenOn = playing.playing },
                modifier = shape?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
            )
        }
    }

    /** The picture's surface, handed to the player's thread as it comes and goes. */
    private val surfaces = object : TextureView.SurfaceTextureListener {
        private var surface: Surface? = null

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val created = Surface(texture)
            surface = created
            onPlayerThread { player?.setVideoSurface(created) }
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            val going = surface
            surface = null
            // The texture is released on the player's thread once the player has let go of it, so the
            // main thread does not wait for the decoder; returning false says it is ours to release.
            val handed = handler.post {
                player?.clearVideoSurface(going)
                going?.release()
                texture.release()
            }
            if (!handed) {
                // The player's thread has already ended with the player: nothing holds the surface.
                going?.release()
                texture.release()
            }
            return false
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        handler.post {
            handler.removeCallbacks(poll)
            player?.removeListener(listener)
            player?.release()
            player = null
            // The measurement of how often playback decrypts a chunk again: numbers only.
            logMeasurement("player released", "chunksDecrypted" to shared.chunksDecrypted, "bytes" to shared.length)
            shared.close()
            thread.quitSafely()
        }
    }

    private fun onPlayerThread(block: () -> Unit) {
        if (released.get()) return
        handler.post { if (!released.get()) block() }
    }

    private fun currentVolume(): Float = PlayerMapping.volumeLevel(
        audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
    )

    private companion object {
        const val POLL_MS = 250L

        /** The player wants a URI; the data source never reads it, and it names nothing. */
        val ITEM_URI: Uri = Uri.parse("vault:item")
    }
}
