package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import dev.ehsanfahad.darkchamber.app.FakeFilePicker
import dev.ehsanfahad.darkchamber.app.FakePhotoPicker
import dev.ehsanfahad.darkchamber.app.system.SystemUiHandoff
import dev.ehsanfahad.darkchamber.feature.vault.api.FilePicker
import dev.ehsanfahad.darkchamber.feature.vault.api.PhotoPicker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.app.testSqlDriver
import dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.NotSavedKind
import dev.ehsanfahad.darkchamber.core.model.NotSavedReason
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The structural session gate: the root starts Locked; a live vault fed through the locked child's
 * `onUnlocked` hook transitions to Unlocked holding that exact session; the unlocked child's `lock()`
 * returns to Locked. Pure component logic — no Compose and no DI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootComponentTest {
    private val testDir = Path(SystemTemporaryDirectory, "rct-${Random.nextULong()}")
    // The Unlocked session scope builds a VaultObjects that resolves the CryptoEngine root single.
    private val koin = koinApplication {
        modules(
            module {
                single<CryptoEngine> { CryptographyKotlinEngine() }
                // The unlocked subtree builds the gallery, which needs the picker seam.
                single<FilePicker> { FakeFilePicker() }
                single<PhotoPicker> { FakePhotoPicker() }
            },
        )
    }.koin

    // Captures the onUnlocked hook the root wires into the locked child, so the test can drive the
    // gate transition without the real LockedComponent's bootstrap machinery (covered elsewhere).
    private var lockedOnUnlocked: ((VaultBootstrap.OpenVault) -> Unit)? = null

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private val handoff = SystemUiHandoff()

    private val backDispatcher = BackDispatcher()

    /** How many times the unlocked subtree asked its host to leave the app. */
    private var leaves = 0

    /**
     * The clock the leave window is measured against — **not** the coroutine scheduler's.
     *
     * Two clocks on purpose, because the behaviour has two halves: the notice disappears on a
     * coroutine (virtual time), while whether a press leaves is re-measured from this. A test can
     * therefore move one without the other, which is how the "the notice is still up but the offer
     * has expired" case below is reachable at all.
     */
    private val timeSource = TestTimeSource()

    private fun root(
        mainContext: CoroutineContext = UnconfinedTestDispatcher(),
        // The unlocked subtree's own dispatcher. Separate from the gate's because most tests here
        // only lock and unlock, while one drives a real gallery and needs the test's scheduler.
        unlockedContext: CoroutineContext = UnconfinedTestDispatcher(),
    ): DefaultRootComponent =
        DefaultRootComponent(
            // With the host's back handler, because the children's contexts are built here rather than
            // handed in: whether back reaches a child at all is this class's behaviour, and nothing
            // that constructs a child directly can show it.
            componentContext = DefaultComponentContext(
                lifecycle = LifecycleRegistry(),
                backHandler = backDispatcher,
            ),
            lockedFactory = { _, onUnlocked -> lockedOnUnlocked = onUnlocked; FakeLockedComponent() },
            unlockedFactory = { ctx, vault, notSaved, onLock ->
                DefaultUnlockedComponent(
                    ctx,
                    vault,
                    koin,
                    mainContext = unlockedContext,
                    leaveWindow = WINDOW,
                    timeSource = timeSource,
                    notSavedRecord = notSaved,
                    onLock = onLock,
                    onLeave = { leaves++ },
                )
            },
            handoff = handoff,
            mainContext = mainContext,
            handoffGrace = GRACE,
        )

    private class FakeLockedComponent : LockedComponent {
        override val state: Value<LockState> = MutableValue(LockState(LockStep.Unlock))
        override fun submitCreate(passphrase: ByteArray) = Unit
        override fun submitPassphraseUnlock(passphrase: ByteArray) = Unit
        override fun startBiometricUnlock() = Unit
        override fun copyRecoveryKey() = Unit
        override fun confirmRecoverySaved() = Unit
        override fun enableBiometric() = Unit
        override fun skipBiometric() = Unit
        override fun dismissError() = Unit
    }

    @Test
    fun starts_locked() {
        assertIs<RootChild.Locked>(root().child.value)
    }

    @Test
    fun feeding_a_vault_transitions_to_unlocked_holding_that_session() {
        val root = root()
        val vault = openVault()

        assertIs<RootChild.Locked>(root.child.value)
        lockedOnUnlocked!!(vault)

        val unlocked = assertIs<RootChild.Unlocked>(root.child.value)
        assertSame(vault.session, unlocked.component.session, "the unlocked child must hold the fed session")
    }

    /**
     * **The host's back button has to reach the unlocked subtree.** The gate builds its children's
     * contexts itself — standalone, so that it owns the lifecycle registry it destroys on lock — and a
     * context built without a back handler quietly gets its own private one. Everything inside then
     * registers its callbacks with a dispatcher nothing will ever call: the system takes the back
     * press instead, the app goes to the background, and the vault auto-locks. On a device that looks
     * like "back closes the app from every screen".
     *
     * Asserted here rather than on the subtree, which is the mistake this test exists to correct: a
     * test that constructs the unlocked component itself passes a working dispatcher in by hand and so
     * can only ever prove that Decompose's stack handles back — never that this class connects it.
     *
     * **Asserted as the handler's identity, not by pressing back.** It used to press: the stack
     * handled back itself, so a press that popped a screen proved the wiring. The stack has since
     * handed back to the navigation animation — two registered handlers pop twice on one gesture —
     * and the animation exists only inside a composition, which a host test has none of. So pressing
     * now proves nothing either way, while *this* is the property the defect was actually about: the
     * subtree watches the host's handler rather than a private one nothing will ever call.
     */
    @Test
    fun the_hosts_back_button_reaches_the_unlocked_subtree() {
        val root = root()
        lockedOnUnlocked!!(openVault())
        val unlocked = assertIs<RootChild.Unlocked>(root.child.value).component
        unlocked.openAlbums()
        assertIs<UnlockedChild.Albums>(unlocked.stack.value.active.instance, "something to come back from")

        assertSame(
            backDispatcher,
            unlocked.backHandler,
            "the gate's child must watch the host's back handler, not a private one",
        )
    }

    @Test
    fun lock_returns_to_locked_and_replaces_the_subtree() {
        val root = root()
        assertIs<RootChild.Locked>(root.child.value)
        lockedOnUnlocked!!(openVault())
        val unlocked = assertIs<RootChild.Unlocked>(root.child.value)

        unlocked.component.lock()

        val relocked = assertIs<RootChild.Locked>(root.child.value)
        assertTrue(relocked.component !== unlocked.component, "lock must build a fresh locked subtree")
    }

    /**
     * The news of a loss outlives the lock that caused it: the record belongs to the gate, so
     * replacing the unlocked subtree — which is what a lock is — leaves it standing, and the next
     * unlocked subtree finds it.
     */
    @Test
    fun what_was_not_saved_survives_the_lock_that_caused_it() {
        val root = root()
        lockedOnUnlocked!!(openVault())
        root.notSaved.report(NotSavedKind.Video, NotSavedReason.Locked, count = 1)

        root.lockIfUnlocked()
        assertIs<RootChild.Locked>(root.child.value)
        lockedOnUnlocked!!(openVault())

        // Read through the *new* subtree: the gate must hand its own record on, not a fresh one.
        val next = assertIs<RootChild.Unlocked>(root.child.value).component
        assertEquals(1, next.notSaved.value.count(NotSavedKind.Video, NotSavedReason.Locked))
    }

    @Test
    fun lock_if_unlocked_locks_an_open_session() {
        val root = root()
        lockedOnUnlocked!!(openVault())
        assertIs<RootChild.Unlocked>(root.child.value)

        root.lockIfUnlocked()

        assertIs<RootChild.Locked>(root.child.value)
    }

    @Test
    fun lock_if_unlocked_is_a_noop_when_already_locked() {
        val root = root()
        root.lockIfUnlocked() // must not throw
        assertIs<RootChild.Locked>(root.child.value)
    }

    // --- leaving from the vault's own grid -----------------------------------------------------
    //
    // Back at the first screen used to fall straight through to the system, which backgrounds the
    // app — and backgrounding locks the vault. So a mis-swipe at the grid cost the whole session,
    // including a running import. The first press is now swallowed and offers to leave; a second
    // inside a short window takes the offer.
    //
    // Driven through the dispatcher **this** class hands the gate, for the reason the wiring test
    // above gives: a test that builds the unlocked component itself supplies a working dispatcher by
    // hand, and so cannot tell a callback registered with the host's handler from one registered with
    // a private handler nothing will ever call.

    @Test
    fun one_press_at_the_vault_is_swallowed_and_offers_to_leave() = runTest {
        val root = root(unlockedContext = StandardTestDispatcher(testScheduler))
        lockedOnUnlocked!!(openVault())
        val unlocked = unlocked(root)

        val handled = backDispatcher.back()

        assertTrue(handled, "the press is answered here rather than falling through to the system")
        assertEquals(0, leaves, "one press does not leave")
        assertEquals(1, unlocked.stack.value.items.size, "and nothing popped")
        assertTrue(unlocked.leaveNotice.value, "the offer is on screen, so the next press is no surprise")
        assertIs<RootChild.Unlocked>(root.child.value, "the session the press would have cost is still open")
    }

    @Test
    fun a_second_press_inside_the_window_leaves_exactly_once() = runTest {
        val root = root(unlockedContext = StandardTestDispatcher(testScheduler))
        lockedOnUnlocked!!(openVault())
        unlocked(root)

        backDispatcher.back()
        // Part-way through the window rather than in the same instant: a window of zero would still
        // pass "two presses in a row leave" if the second press were measured at no elapsed time.
        timeSource += WINDOW / 2
        backDispatcher.back()

        assertEquals(1, leaves, "the second press leaves, and asks for it once")
    }

    @Test
    fun a_second_press_after_the_window_arms_again_instead_of_leaving() = runTest {
        val root = root(unlockedContext = StandardTestDispatcher(testScheduler))
        lockedOnUnlocked!!(openVault())
        val unlocked = unlocked(root)

        backDispatcher.back()
        elapse(WINDOW * 2)
        assertFalse(unlocked.leaveNotice.value, "the offer expired on its own")
        backDispatcher.back()

        assertEquals(0, leaves, "a press long after the first is a first press, not a confirmation")
        assertTrue(unlocked.leaveNotice.value, "so it offers again")
    }

    /**
     * **The decision does not trust the notice.** Taking the notice off screen is a coroutine, and a
     * coroutine can be late — the dispatcher is busy, the app was frozen in the background, the
     * window passed while a long import held the main thread. The elapsed time is therefore measured
     * again on every press, so a notice that has outstayed the offer cannot let the next press
     * straight out of the app.
     *
     * The two clocks move independently here: the monotonic source is past the window while the
     * notice's timer has not run at all.
     */
    @Test
    fun a_press_the_offer_has_outlived_cannot_leave_even_while_the_notice_is_still_up() = runTest {
        val root = root(unlockedContext = StandardTestDispatcher(testScheduler))
        lockedOnUnlocked!!(openVault())
        val unlocked = unlocked(root)

        backDispatcher.back()
        timeSource += WINDOW * 2 // time passed; the effect that clears the notice did not run
        assertTrue(unlocked.leaveNotice.value, "the stale notice is exactly the condition under test")

        backDispatcher.back()

        assertEquals(0, leaves, "an expired offer is not a confirmation, whatever is on screen")
    }

    @Test
    fun the_notice_clears_itself_once_the_window_passes() = runTest {
        val root = root(unlockedContext = StandardTestDispatcher(testScheduler))
        lockedOnUnlocked!!(openVault())
        val unlocked = unlocked(root)

        backDispatcher.back()
        assertTrue(unlocked.leaveNotice.value)
        elapse(WINDOW)

        assertFalse(unlocked.leaveNotice.value, "a notice nobody answered does not stay on screen")
    }

    /**
     * **Above the root, back still belongs to the navigation animation.** The callback is enabled
     * only while the grid is the one screen, so a press on a sub-screen is not answered here at all —
     * which in a host test means not answered by anything, the animation existing only inside a
     * composition. Popping is the component's own entry point, and it still pops exactly one screen.
     */
    @Test
    fun a_press_on_a_sub_screen_is_not_answered_at_the_root() = runTest {
        val root = root(unlockedContext = StandardTestDispatcher(testScheduler))
        lockedOnUnlocked!!(openVault())
        val unlocked = unlocked(root)
        unlocked.openAlbums()

        val handled = backDispatcher.back()

        assertFalse(handled, "the root's callback must be disabled while something is stacked on it")
        assertEquals(0, leaves, "and a press meant for a screen must never leave the app")
        assertEquals(2, unlocked.stack.value.items.size, "nothing popped, nothing left")
        assertFalse(unlocked.leaveNotice.value, "and nothing offered to leave")

        unlocked.onBack()

        assertEquals(1, unlocked.stack.value.items.size, "the way back out of a sub-screen still works")
    }

    /**
     * **Arriving back at the grid starts unarmed.** A press at the grid, a trip into albums and a
     * press on the way back is three presses by one finger, and the third must not be read as a
     * confirmation of the first. The clock is deliberately not moved here: the offer has not expired,
     * so only disarming on navigation can make this pass.
     */
    @Test
    fun coming_back_to_the_vault_from_a_sub_screen_starts_unarmed() = runTest {
        val root = root(unlockedContext = StandardTestDispatcher(testScheduler))
        lockedOnUnlocked!!(openVault())
        val unlocked = unlocked(root)

        backDispatcher.back() // at the grid: the offer stands
        unlocked.openAlbums()
        assertFalse(unlocked.leaveNotice.value, "and it goes with the screen it was made on")
        unlocked.onBack()

        backDispatcher.back()

        assertEquals(0, leaves, "back at the grid, this is a first press again")
        assertTrue(unlocked.leaveNotice.value)
    }

    private fun unlocked(root: DefaultRootComponent): UnlockedComponent =
        assertIs<RootChild.Unlocked>(root.child.value).component

    /** Move both clocks: the one the decision reads and the one the notice's timer runs on. */
    private fun TestScope.elapse(duration: Duration) {
        timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    // --- the system-UI handoff exemption -------------------------------------------------------
    //
    // A file picker is a separate activity, so reaching it *is* backgrounding us. Locking there
    // destroys the screen the picker's result comes back to, which makes import impossible on
    // Android. The exemption defers the lock; it never abandons it.

    @Test
    fun backgrounding_during_a_picker_handoff_does_not_lock() = runTest {
        val root = root(StandardTestDispatcher(testScheduler))
        lockedOnUnlocked!!(openVault())

        handoff.begin() // the picker was launched; we are being stopped because of it
        root.lockIfUnlocked()

        assertIs<RootChild.Unlocked>(root.child.value, "the session must survive the pick")
        handoff.end()
    }

    @Test
    fun returning_from_the_picker_keeps_the_session_and_disarms_the_watchdog() = runTest {
        val root = root(StandardTestDispatcher(testScheduler))
        lockedOnUnlocked!!(openVault())

        handoff.begin()
        root.lockIfUnlocked() // Android onStop, on the way into SAF
        handoff.end() // the user picked a file
        root.onForeground() // Android onStart, on the way back

        advanceTimeBy(GRACE * 4) // the watchdog must be gone, not merely deferred
        runCurrent()

        assertIs<RootChild.Unlocked>(root.child.value, "returning must not lock the vault later")
    }

    @Test
    fun an_abandoned_picker_locks_once_the_grace_expires() = runTest {
        val root = root(StandardTestDispatcher(testScheduler))
        lockedOnUnlocked!!(openVault())

        handoff.begin()
        root.lockIfUnlocked() // into the picker, and the user walks away
        advanceTimeBy(GRACE / 2)
        runCurrent()
        assertIs<RootChild.Unlocked>(root.child.value, "still inside the grace period")

        advanceTimeBy(GRACE)
        runCurrent()

        assertIs<RootChild.Locked>(
            root.child.value,
            "a picker left open must not keep the MasterKey alive indefinitely",
        )
    }

    @Test
    fun a_plain_background_still_locks_immediately_while_no_handoff_is_in_flight() = runTest {
        val root = root(StandardTestDispatcher(testScheduler))
        lockedOnUnlocked!!(openVault())

        root.lockIfUnlocked() // home button — nothing was handed off

        assertIs<RootChild.Locked>(root.child.value, "the exemption must not leak into ordinary backgrounding")
    }

    @Test
    fun repeated_backgrounding_during_one_pick_leaves_no_orphan_watchdog() = runTest {
        val root = root(StandardTestDispatcher(testScheduler))
        lockedOnUnlocked!!(openVault())

        handoff.begin()
        root.lockIfUnlocked() // onStop into the picker
        advanceTimeBy(GRACE / 4)
        runCurrent()
        root.lockIfUnlocked() // a second onStop during the same pick (the provider took over)
        handoff.end() // the user picked a file
        root.onForeground() // and came back

        advanceTimeBy(GRACE * 4)
        runCurrent()

        assertIs<RootChild.Unlocked>(
            root.child.value,
            "a watchdog armed by an earlier stop must not lock the vault while the user is using it",
        )
    }

    // A live vault with a cheap real session (Argon2id m=256KiB/t=1) + temp storage + in-memory catalog.
    private fun openVault(): VaultBootstrap.OpenVault {
        val engine = CryptographyKotlinEngine()
        val cheap = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)
        val session: VaultSession =
            VaultSession.createVault(engine, DefaultKeyDerivation(engine), "pw".encodeToByteArray(), cheap).session
        val root = Path(testDir, "vault-${Random.nextULong()}")
        SystemFileSystem.createDirectories(root)
        val catalog = (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value
        return VaultBootstrap.OpenVault(session, VaultStorage(root), catalog)
    }

    private companion object {
        /** Virtual time, so the value only has to be non-zero — the production grace is 2 minutes. */
        val GRACE: Duration = 2.minutes

        /**
         * The leave window these tests measure against. A value of its own rather than the
         * production constant: every assertion here is about a press landing inside or outside the
         * window, and none of them should change meaning if the app's window is retuned.
         */
        val WINDOW: Duration = 5.seconds
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
