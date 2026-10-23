package dev.ehsanfahad.darkchamber.feature.camera.domain

import dev.ehsanfahad.darkchamber.feature.camera.api.MicrophonePermission

/**
 * Space kept free beyond the recording and its encrypted copy: the `.dchm` overhead, the thumbnail,
 * the catalog's write, and room for the rest of the phone. A starting value; the device run measures
 * what a clip's encrypt actually needs and this is set from that.
 */
internal const val RECORDING_RESERVE_BYTES: Long = 200_000_000L

/**
 * The smallest limit worth starting a recording with — about ten seconds of 4K at the ~50 Mbit/s a
 * phone records it at. Below it a clip would stop almost as soon as it began. A starting value; the
 * device run measures the real bitrate and this is set from that.
 */
internal const val RECORDING_FLOOR_BYTES: Long = 64_000_000L

/** What the free space allows a recording, decided before it starts. */
internal sealed interface RecordingRoom {
    /** Start, and let the platform stop the file at [bytes]. */
    data class Limit(val bytes: Long) : RecordingRoom

    /** Measured, and too little: the limit would be under [RECORDING_FLOOR_BYTES]. */
    data object NotEnough : RecordingRoom

    /** The platform could not say how much space there is. */
    data object Unknown : RecordingRoom
}

/**
 * The size limit for a recording, from the free space on the vault's volume.
 *
 * Half of what is left after [RECORDING_RESERVE_BYTES]: until its encrypt finishes, the plaintext
 * clip and its encrypted copy are both on the disk, at about the same size. So a clip stopped at the
 * limit can always be saved, provided nothing else fills the disk meanwhile.
 *
 * Unknown free space refuses rather than recording without a limit: an unbounded clip could take the
 * whole disk and leave its plaintext in `tmp/` with no room to encrypt it.
 */
internal fun recordingRoom(freeBytes: Long?): RecordingRoom {
    if (freeBytes == null) return RecordingRoom.Unknown
    val limit = (freeBytes - RECORDING_RESERVE_BYTES) / 2
    return if (limit >= RECORDING_FLOOR_BYTES) RecordingRoom.Limit(limit) else RecordingRoom.NotEnough
}

/**
 * The status line's "left": the limit fixed at start less what has been recorded. Measured against
 * the limit, never the disk's free space, so it reaches zero exactly when the recording stops itself.
 * Never negative, though a platform may overshoot by a frame.
 */
internal fun roomLeft(limitBytes: Long, recordedBytes: Long): Long = (limitBytes - recordedBytes).coerceAtLeast(0)

/** The one line that would help turn sound on, if any. */
internal enum class MicrophoneHint {
    None,

    /** The system will still ask. */
    Ask,

    /** Only the system's settings can change it now. */
    Settings,
}

/** Whether a recording has sound, and what to say when it does not. */
internal data class RecordingSound(val withAudio: Boolean, val hint: MicrophoneHint)

/**
 * Sound only with the microphone granted. A refusal never blocks recording; it records silence, and
 * the hint says how to change that — a re-ask while the system still asks, Settings once it will not.
 */
internal fun recordingSound(permission: MicrophonePermission): RecordingSound =
    when (permission) {
        MicrophonePermission.Granted -> RecordingSound(withAudio = true, hint = MicrophoneHint.None)
        MicrophonePermission.NotAsked, MicrophonePermission.Denied -> RecordingSound(withAudio = false, hint = MicrophoneHint.Ask)
        MicrophonePermission.PermanentlyDenied -> RecordingSound(withAudio = false, hint = MicrophoneHint.Settings)
    }
