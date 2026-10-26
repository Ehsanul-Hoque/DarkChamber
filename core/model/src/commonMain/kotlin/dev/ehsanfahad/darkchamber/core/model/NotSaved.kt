package dev.ehsanfahad.darkchamber.core.model

/**
 * The vocabulary for telling the user that something they made did not land in the vault as they
 * asked. It lives here, where every feature can reach it, because what reports a loss (a feature's
 * own queue) and what says so (the app, after the next unlock) are in different modules — and the
 * report has to outlive the session teardown that usually causes it.
 *
 * **Counts, kinds and reasons only.** Never an id, a name, an album or anything derived from what
 * was being saved: a loss is told after a lock, from somewhere the lock does not reach, so it may
 * carry nothing a lock should have taken with it.
 */
enum class NotSavedKind { Photo, Video }

/**
 * Why. A reason is named only where the code knows it: [OutOfSpace] only from
 * `VaultError.Storage.Full`, [TooShort] only from a recording that captured no frames, and
 * everything the code cannot name is [CouldNotSave].
 */
enum class NotSavedReason {
    /** The vault locked first, and a lock never waits for a save. */
    Locked,

    /** The platform reported the device out of space at the write. */
    OutOfSpace,

    /** A recording ended before its first frame, so there was nothing to keep. */
    TooShort,

    /** Anything else: no cause is claimed. */
    CouldNotSave,

    /**
     * Not a loss: the item **was** saved, but unfiled, because its album was deleted while it was
     * being saved. Reported the same way because it is the same kind of news — where something went
     * differs from where it was aimed, and the screen that would have said so may be gone.
     */
    SavedUnfiled,
}

/** Where a feature reports what did not make it; the app keeps and shows it. */
fun interface NotSavedReporter {
    fun report(kind: NotSavedKind, reason: NotSavedReason, count: Int)
}
