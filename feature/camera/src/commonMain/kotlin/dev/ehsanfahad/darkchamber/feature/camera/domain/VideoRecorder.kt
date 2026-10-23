package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.core.model.NotSavedKind
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason
import dev.ehsanfahad.darkchamber.core.model.NotSavedReporter
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import dev.ehsanfahad.darkchamber.feature.camera.api.ClipFile
import dev.ehsanfahad.darkchamber.feature.camera.api.ClipFiles
import dev.ehsanfahad.darkchamber.feature.camera.api.FreeSpace
import dev.ehsanfahad.darkchamber.feature.camera.api.Recording
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingEnd
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingFailure
import dev.ehsanfahad.darkchamber.feature.camera.api.RecordingProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a close waits for the platform to finalize a recording before giving up on it. Long
 * enough for a phone to write out the end of a 4K clip; short enough that the camera's in-use
 * indicator never stays on behind a screen the user has left.
 */
internal val FINALIZE_TIMEOUT: Duration = 3.seconds

/**
 * One camera screen's recordings, from start to the video lane or the bin, on every way out.
 *
 * - **Start** checks the room first ([recordingRoom]) and refuses with a notice below the floor or
 *   when free space is unknown, and while the lane still holds a clip — one plaintext file at a time.
 *   Otherwise it reserves a fresh clip file and records into it with the size limit and, only when
 *   sound is wanted and the microphone is granted, sound.
 * - **Stop** finalizes. A clip that finalized — normally, at the size limit, or with the disk full,
 *   which earns its one encrypt attempt — goes to the [lane]. Anything else is deleted at once: a clip
 *   with no frames is too short to keep, and a file the platform reports broken is not trusted.
 * - **Close** stops, waits for the hand-over and **only then** releases the camera: unbinding
 *   mid-capture would abort the file. If the platform has not finalized within [FINALIZE_TIMEOUT],
 *   the clip is abandoned so the camera is released regardless.
 * - **A lock** abandons. The recording's end-watch runs in [scope], the session's, so a lock cancels
 *   it; the clip's file is deleted and the clip recorded as lost to the lock. A recording the
 *   teardown released before cancelling the scope ends as [RecordingEnd.Abandoned], and is recorded
 *   the same way — only a lock's teardown releases the camera under a recording.
 *
 * Until [close], what went wrong is this screen's to show ([RecorderState.notice]); after it, it goes
 * to [notSaved]. Main-thread confined, like the screen: [scope] runs on the main dispatcher.
 */
internal class VideoRecorder(
    private val camera: CameraController,
    private val lane: VideoLane,
    private val clips: ClipFiles,
    private val freeSpace: FreeSpace,
    private val albumId: ByteArray?,
    private val scope: CoroutineScope,
    private val notSaved: NotSavedReporter,
    // Whether the person wants sound — the microphone toggle — read at each start.
    private val soundWanted: () -> Boolean,
) {
    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private var take: Take? = null
    private var closed = false

    /** Start recording. Returns `false`, with a notice when there is one to give, when it cannot. */
    fun start(): Boolean {
        if (closed || take != null || lane.state.value.busy) return false
        val limit = when (val room = recordingRoom(freeSpace.availableBytes())) {
            is RecordingRoom.Limit -> room.bytes
            RecordingRoom.NotEnough -> return refuse(RecorderNotice.NotEnoughSpace)
            RecordingRoom.Unknown -> return refuse(RecorderNotice.SpaceUnknown)
        }
        val sound = recordingSound(camera.microphonePermission.value, soundWanted())
        val clip = clips.newClip()
        val recording = camera.record(clip.path, limit, sound.withAudio)
        val started = Take(clip, recording)
        take = started
        _state.update { RecorderState(active = ActiveRecording(limit, sound.withAudio, recording.progress)) }
        started.watch = scope.launch { settle(started, recording.awaitEnd()) }
        // Cancelled before the end arrived — a lock. Nothing else cancels the session's scope.
        started.watch.invokeOnCompletion { if (!started.settled) abandonToLock(started) }
        return true
    }

    /** Stop recording and keep the clip. The end, and the hand-over, follow from the platform. */
    fun stop() {
        val current = take ?: return
        _state.update { it.copy(stopping = true) }
        current.recording.stop()
    }

    /**
     * The screen is closing: keep a recording in progress, then [release] the camera. Suspends until
     * the clip is with the lane or has been given up; call it from a scope that outlives the screen.
     */
    suspend fun close(release: () -> Unit) {
        closed = true
        val current = take
        if (current == null) {
            release()
            return
        }
        current.recording.stop()
        val handedOver = withTimeoutOrNull(FINALIZE_TIMEOUT) { current.watch.join() }
        if (handedOver == null) {
            current.timedOut = true
            // Releasing the camera ends the recording as abandoned, which settles it below.
            release()
            current.watch.join()
        } else {
            release()
        }
    }

    private fun settle(settling: Take, end: RecordingEnd) {
        settling.settled = true
        take = null
        when (end) {
            RecordingEnd.Finalized -> handOver(settling.clip, notice = null)
            RecordingEnd.FinalizedAtSizeLimit -> handOver(settling.clip, notice = RecorderNotice.StoppedAtLimit)
            // The disk filled before our limit — another app, most likely. Stopping may have left
            // room, so the encrypt gets one attempt; the lane deletes the file at once if it fails.
            is RecordingEnd.Failed -> when (end.reason) {
                RecordingFailure.StorageFull -> handOver(settling.clip, notice = null)
                RecordingFailure.Other -> discard(settling.clip, RecorderNotice.RecordingFailed, NotSavedReason.CouldNotSave)
            }
            RecordingEnd.NoFrames -> discard(settling.clip, RecorderNotice.TooShort, NotSavedReason.TooShort)
            RecordingEnd.Abandoned ->
                if (settling.timedOut) {
                    discard(settling.clip, RecorderNotice.RecordingFailed, NotSavedReason.CouldNotSave)
                } else {
                    abandonToLock(settling)
                }
        }
    }

    private fun handOver(clip: ClipFile, notice: RecorderNotice?) {
        // Never refused in practice — start checks the lane — but a refused clip is ours to delete.
        if (!lane.save(clip, albumId)) {
            discard(clip, RecorderNotice.RecordingFailed, NotSavedReason.CouldNotSave)
            return
        }
        _state.update { RecorderState(notice = if (closed) null else notice) }
    }

    private fun discard(clip: ClipFile, notice: RecorderNotice, reason: NotSavedReason) {
        clip.delete()
        if (closed) notSaved.report(NotSavedKind.Video, reason, 1)
        _state.update { RecorderState(notice = if (closed) null else notice) }
    }

    private fun abandonToLock(abandoned: Take) {
        abandoned.settled = true
        if (take === abandoned) take = null
        abandoned.clip.delete()
        notSaved.report(NotSavedKind.Video, NotSavedReason.Locked, 1)
        _state.update { RecorderState() }
    }

    private fun refuse(notice: RecorderNotice): Boolean {
        _state.update { it.copy(notice = notice) }
        return false
    }

    /** The recording in progress and its file, until it has been handed over or given up. */
    private class Take(val clip: ClipFile, val recording: Recording) {
        lateinit var watch: Job
        var settled = false
        var timedOut = false
    }
}

/** What the camera screen says about the latest recording, until the next one. */
internal enum class RecorderNotice {
    /** The room check refused: the limit would be under the floor. */
    NotEnoughSpace,

    /** The room check could not be made: the platform did not say how much space there is. */
    SpaceUnknown,

    /** Stopped before the first frame; nothing to keep. */
    TooShort,

    /** The platform stopped at our size limit; the clip was kept. */
    StoppedAtLimit,

    /** The platform ended it with an error, or never finalized it. */
    RecordingFailed,
}

/** A recording in progress, for the status line: the limit "left" counts down from, sound, and progress. */
internal data class ActiveRecording(
    val limitBytes: Long,
    val withAudio: Boolean,
    val progress: StateFlow<RecordingProgress>,
)

/** The recorder as the screen sees it. */
internal data class RecorderState(
    val active: ActiveRecording? = null,
    /** Stop has been asked for and the platform has not finalized yet. */
    val stopping: Boolean = false,
    val notice: RecorderNotice? = null,
)
