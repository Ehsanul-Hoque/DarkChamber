package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.DefaultComponentContext
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
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

    private fun root(mainContext: CoroutineContext = UnconfinedTestDispatcher()): DefaultRootComponent =
        DefaultRootComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            lockedFactory = { _, onUnlocked -> lockedOnUnlocked = onUnlocked; FakeLockedComponent() },
            unlockedFactory = { ctx, vault, onLock -> DefaultUnlockedComponent(ctx, vault, koin, mainContext = UnconfinedTestDispatcher(), onLock = onLock) },
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
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
