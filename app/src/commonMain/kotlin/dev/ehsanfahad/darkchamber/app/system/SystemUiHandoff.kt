package dev.ehsanfahad.darkchamber.app.system

/**
 * Tracks a round-trip to system UI that **we** launched — Android's Storage Access Framework today,
 * the photo picker and the share sheet later.
 *
 * Those are separate activities, so the OS stops our app while the user is choosing a file. The
 * on-background auto-lock would therefore fire *before* the result arrives: the session is zeroized,
 * the feature scope is cancelled, and the picker's callback comes back to a screen that no longer
 * exists. Import is impossible on Android without this exemption.
 *
 * What is exempt is narrow, and deliberately so:
 *
 * - **only a handoff we started ourselves** — an ordinary background (home button, another app,
 *   the screen locking) still locks immediately;
 * - **only until it returns** — the gate arms a watchdog while a handoff is in flight, so a picker
 *   the user walks away from still locks (see `DefaultRootComponent`);
 * - the app-switcher card stays blank throughout (`FLAG_SECURE` on Android, the blur on iOS), so
 *   nothing of the vault is visible while the picker is up.
 *
 * The cost is stated plainly in `docs/THREAT_MODEL.md`: the MasterKey stays in memory for the length
 * of the pick.
 *
 * **Main-thread confined.** Pickers are presented from a screen and the gate reads [inFlight] from
 * the host's lifecycle callback, so the counter needs no synchronisation — and must not be touched
 * from anywhere else.
 */
class SystemUiHandoff {

    // A counter, not a flag: nothing today overlaps two pickers, but a stray end() must not cancel
    // an unrelated handoff that is still open.
    private var depth = 0

    /** True while at least one self-launched system-UI round-trip is outstanding. */
    val inFlight: Boolean get() = depth > 0

    /**
     * Runs [block] as a system-UI handoff. The exemption is released on every path — normal return,
     * failure, or cancellation — so a cancelled pick cannot leave the vault permanently exempt.
     */
    suspend fun <T> during(block: suspend () -> T): T {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }

    internal fun begin() {
        depth++
    }

    internal fun end() {
        if (depth > 0) depth--
    }
}
