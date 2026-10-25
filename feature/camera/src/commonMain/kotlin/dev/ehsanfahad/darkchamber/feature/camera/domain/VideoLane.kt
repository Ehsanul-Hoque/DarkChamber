package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.core.model.NotSavedKind
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason
import dev.ehsanfahad.darkchamber.core.model.NotSavedReporter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.VaultItemKind
import dev.ehsanfahad.darkchamber.core.vault.ImportPhase
import dev.ehsanfahad.darkchamber.core.vault.WhenAlbumMissing
import dev.ehsanfahad.darkchamber.feature.camera.api.ClipFile
import dev.ehsanfahad.darkchamber.feature.camera.api.VideoContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/**
 * Saves recorded clips into the vault: each finished recording's file is encrypted into the vault
 * and then deleted.
 *
 * **One clip at a time.** From [save] until that clip is saved or has failed, the lane is
 * [VideoLaneState.busy] and refuses another, so there is never a second plaintext file in `tmp/` —
 * and the recording's size limit, which assumes no other encrypt is pending, stays true. It is
 * **apart from the photo queue**, so photos never wait behind a clip's encrypt.
 *
 * **The file is deleted on every way out**: saved, failed, abandoned by a lock, or handed over as the
 * lock landed. A file this lane was given never outlives the clip's turn in it. The purge of `tmp/`
 * on lock and on launch stays the backstop for a crash, not the plan.
 *
 * **[scope] is the session's, and the lane outlives any one camera screen**: a clip keeps saving after
 * the camera closes, and a camera opened again meanwhile finds the lane still busy. A lock cancels
 * [scope]; the import polls it on every read and stops, leaving no object, row or thumbnail.
 *
 * **What went wrong goes where someone will see it.** While a camera screen is open ([screenOpen])
 * a failure, or a clip saved unfiled because its album went, is the screen's to show through
 * [VideoLaneState.notice]. Once it has closed, the same goes to [notSaved], which the unlocked
 * screen shows at once. A clip a lock took always goes to [notSaved]: the lock takes the screen too.
 *
 * Main-thread confined like the screen: [scope] runs on the main dispatcher and only the encrypt moves
 * to [ioContext].
 */
internal class VideoLane(
    private val vault: CaptureVault,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext,
    private val notSaved: NotSavedReporter,
    private val nameFor: (epochMillis: Long, container: VideoContainer) -> String = ::videoName,
) {
    private val _state = MutableStateFlow(VideoLaneState())
    val state: StateFlow<VideoLaneState> = _state.asStateFlow()

    /**
     * Whether a camera screen is showing — set by the screen as it opens and closes. Opening clears a
     * notice left from an earlier screen, which was about a clip that screen already showed or never
     * could.
     */
    var screenOpen: Boolean = false
        set(open) {
            field = open
            if (open) _state.update { it.copy(notice = null) }
        }

    /**
     * Hand over a finished clip, to be filed in [albumId] (`null` for unfiled). Returns `false`, and
     * leaves the clip with its caller to delete, only while another clip is still being saved.
     */
    fun save(clip: ClipFile, albumId: ByteArray?): Boolean {
        if (_state.value.busy) return false
        _state.update { it.copy(busy = true, progress = null) }
        val size = clip.size()
        val name = nameFor(clock(), clip.container)
        val destination = albumId?.copyOf()
        // Written on the encrypt's thread, read once the job is complete.
        val result = Result()
        val job = scope.launch {
            withContext(ioContext) {
                // Assigned here rather than as withContext's value: a lock landing after the row
                // committed cancels withContext on the way out, and would lose the fact that the clip
                // is in the vault.
                result.outcome = vault.import(
                    kind = VaultItemKind.FILE.code,
                    name = name,
                    mime = clip.container.mime,
                    albumId = destination,
                    isActive = { scope.isActive },
                    onProgress = { phase, bytes -> if (phase == ImportPhase.ENCRYPTING) report(bytes, size) },
                    whenAlbumMissing = WhenAlbumMissing.FileUnfiled { result.filedUnfiled = true },
                    openSource = clip::open,
                )
            }
        }
        // On completion rather than in a finally: a job cancelled before it starts never runs its
        // body, and the file must go either way. Completion comes only after the import has returned,
        // so nothing is deleted from under a read.
        job.invokeOnCompletion { finish(clip, result) }
        return true
    }

    private fun report(bytes: Long, size: Long?) {
        if (size == null || size <= 0) return
        val fraction = (bytes.toDouble() / (2.0 * size)).coerceIn(0.0, 1.0).toFloat()
        _state.update { it.copy(progress = fraction) }
    }

    private fun finish(clip: ClipFile, result: Result) {
        clip.delete()
        val outcome = result.outcome
        val onScreen = screenOpen && scope.isActive
        val notice: VideoNotice? = when {
            outcome is Outcome.Ok -> {
                if (result.filedUnfiled) say(NotSavedReason.SavedUnfiled, VideoNotice.SavedUnfiled, onScreen) else null
            }
            // Whatever the import answered, it stopped because the session went.
            !scope.isActive -> {
                notSaved.report(NotSavedKind.Video, NotSavedReason.Locked, 1)
                null
            }
            outcome is Outcome.Err && outcome.error == VaultError.Storage.Full ->
                say(NotSavedReason.OutOfSpace, VideoNotice.OutOfSpace, onScreen)
            else -> say(NotSavedReason.CouldNotSave, VideoNotice.SaveFailed, onScreen)
        }
        _state.update {
            when (outcome) {
                is Outcome.Ok -> it.copy(busy = false, progress = null, saved = it.saved + 1, lastSavedId = outcome.value, notice = notice)
                else -> it.copy(busy = false, progress = null, notice = notice)
            }
        }
    }

    // The screen's notice when there is a screen to show it, the record's line otherwise.
    private fun say(reason: NotSavedReason, notice: VideoNotice, onScreen: Boolean): VideoNotice? {
        if (onScreen) return notice
        notSaved.report(NotSavedKind.Video, reason, 1)
        return null
    }

    private class Result {
        @Volatile var outcome: Outcome<ByteArray>? = null

        @Volatile var filedUnfiled: Boolean = false
    }
}

/** What the camera screen says about the latest clip, until the next one or until it is reopened. */
internal enum class VideoNotice { SaveFailed, OutOfSpace, SavedUnfiled }

/**
 * The lane as the screen sees it: whether a clip is being saved and how far its encrypt has got
 * (`null` when there is nothing to measure), how many clips this session has saved, the id of the
 * newest, and the latest notice. Holds an item **id**, never content.
 */
internal class VideoLaneState(
    val busy: Boolean = false,
    val progress: Float? = null,
    val saved: Int = 0,
    val lastSavedId: ByteArray? = null,
    val notice: VideoNotice? = null,
) {
    fun copy(
        busy: Boolean = this.busy,
        progress: Float? = this.progress,
        saved: Int = this.saved,
        lastSavedId: ByteArray? = this.lastSavedId,
        notice: VideoNotice? = this.notice,
    ): VideoLaneState = VideoLaneState(busy, progress, saved, lastSavedId, notice)

    override fun toString(): String =
        "VideoLaneState(busy=$busy, progress=$progress, saved=$saved, " +
            "last=${if (lastSavedId == null) "none" else "present"}, notice=$notice)"
}
