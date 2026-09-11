package dev.ehsanfahad.darkchamber.app.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import dev.ehsanfahad.darkchamber.app.DirectoryVaultRootResolver
import dev.ehsanfahad.darkchamber.app.FakeBiometricAuthenticator
import dev.ehsanfahad.darkchamber.app.FakeSecureKeyStore
import dev.ehsanfahad.darkchamber.app.VaultBootstrap
import dev.ehsanfahad.darkchamber.app.di.CatalogProvider
import dev.ehsanfahad.darkchamber.app.testSqlDriver
import dev.ehsanfahad.darkchamber.core.crypto.BiometricAuthenticator
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The locked surface's state machine (§3.3), driven by a real [VaultBootstrap] over a temp root +
 * in-memory catalog + fakes. Screens (LockScreens) are compile-only; this pins the create → recovery
 * → enable → unlock flow and the biometric error → forced-passphrase transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockedComponentTest {
    private val testDir = Path(SystemTemporaryDirectory, "lct-${Random.nextULong()}")
    private val vaultBase = Path(testDir, "vault")
    private val sks = FakeSecureKeyStore()

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    private fun bootstrap(): VaultBootstrap =
        VaultBootstrap(
            rootResolver = DirectoryVaultRootResolver(vaultBase),
            engine = CryptographyKotlinEngine(),
            catalogProvider = CatalogProvider { CatalogStore.create(testSqlDriver()) },
            secureKeyStore = sks,
        )

    private fun component(
        authenticator: BiometricAuthenticator = FakeBiometricAuthenticator(),
        mainContext: CoroutineContext = UnconfinedTestDispatcher(),
        workContext: CoroutineContext = UnconfinedTestDispatcher(),
        onUnlocked: (VaultBootstrap.OpenVault) -> Unit = {},
    ): DefaultLockedComponent =
        DefaultLockedComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            bootstrap = bootstrap(),
            authenticator = authenticator,
            mainContext = mainContext,
            workContext = workContext,
            onUnlocked = onUnlocked,
        )

    private fun pw() = "correct-horse-battery".encodeToByteArray()

    @Test
    fun fresh_root_starts_in_create() {
        assertEquals(LockStep.Create, component().state.value.step)
    }

    @Test
    fun create_flow_shows_recovery_then_enable_then_unlocks() = runTest {
        var unlocked: VaultBootstrap.OpenVault? = null
        val c = component(onUnlocked = { unlocked = it })

        c.submitCreate(pw())
        val recovery = assertIs<LockStep.Recovery>(c.state.value.step)
        assertTrue(recovery.recoveryKey.isNotBlank(), "recovery key must be shown once")

        c.confirmRecoverySaved()
        assertEquals(LockStep.EnableBiometric, c.state.value.step)

        c.skipBiometric()
        assertTrue(unlocked != null, "skipping biometric must still unlock")
        unlocked?.session?.close()
    }

    // Argon2id at the production cost is ~1 s of CPU on a fast device. Run on the main context it
    // freezes the UI — including the progress indicator that is meant to be reporting it — and
    // risks an ANR on a slow one. These two pin the handoff by giving the work context a scheduler
    // of its own: if the derivation ran inline on the main context, draining the main scheduler
    // alone would already have produced the result.

    @Test
    fun creating_a_vault_derives_off_the_main_context() = runTest {
        val workScheduler = TestCoroutineScheduler()
        val c = component(
            mainContext = StandardTestDispatcher(testScheduler),
            workContext = StandardTestDispatcher(workScheduler),
        )

        c.submitCreate(pw())
        advanceUntilIdle() // drain the main context only

        assertEquals(LockStep.Create, c.state.value.step, "derivation must not have run on the main context")
        assertTrue(c.state.value.busy, "the gate is busy while the work context holds the derivation")

        workScheduler.advanceUntilIdle() // now let the derivation run
        advanceUntilIdle() // and the state write land back on the main context

        assertIs<LockStep.Recovery>(c.state.value.step, "the derivation's result still has to arrive")
    }

    @Test
    fun unlocking_derives_off_the_main_context() = runTest {
        component().submitCreate(pw()) // seed a vault on the default unconfined contexts
        val workScheduler = TestCoroutineScheduler()
        var unlocked: VaultBootstrap.OpenVault? = null
        val c = component(
            mainContext = StandardTestDispatcher(testScheduler),
            workContext = StandardTestDispatcher(workScheduler),
            onUnlocked = { unlocked = it },
        )

        c.submitPassphraseUnlock(pw())
        advanceUntilIdle()

        assertTrue(unlocked == null, "the passphrase must not be verified on the main context")

        workScheduler.advanceUntilIdle()
        advanceUntilIdle()

        assertTrue(unlocked != null, "the unlock still has to complete")
        unlocked?.session?.close()
    }

    @Test
    fun enable_biometric_enrolls_then_unlocks() = runTest {
        var unlocked: VaultBootstrap.OpenVault? = null
        val c = component(onUnlocked = { unlocked = it })
        c.submitCreate(pw())
        c.confirmRecoverySaved()

        c.enableBiometric()

        assertTrue(unlocked != null, "enabling biometric must unlock")
        unlocked?.session?.close()
    }

    @Test
    fun returning_user_unlocks_with_the_passphrase() = runTest {
        // Seed a vault first.
        component().submitCreate(pw()).also { }
        var unlocked: VaultBootstrap.OpenVault? = null
        val c = component(onUnlocked = { unlocked = it })
        assertEquals(LockStep.Unlock, c.state.value.step)

        c.submitPassphraseUnlock(pw())
        assertTrue(unlocked != null, "correct passphrase must unlock")
        unlocked?.session?.close()
    }

    @Test
    fun a_wrong_passphrase_shows_an_error_and_does_not_unlock() = runTest {
        component().submitCreate(pw())
        var unlocked: VaultBootstrap.OpenVault? = null
        val c = component(onUnlocked = { unlocked = it })

        c.submitPassphraseUnlock("wrong".encodeToByteArray())
        assertEquals(LockError.WrongPassphrase, c.state.value.error)
        assertTrue(unlocked == null)
    }

    @Test
    fun after_enrollment_biometric_unlock_succeeds_and_offers_biometric() = runTest {
        // Create + enroll, then a fresh returning component sees the hardware slot.
        val c1 = component()
        c1.submitCreate(pw())
        c1.confirmRecoverySaved()
        c1.enableBiometric()

        var unlocked: VaultBootstrap.OpenVault? = null
        val c2 = component(onUnlocked = { unlocked = it })
        assertTrue(c2.state.value.hasBiometric, "an enrolled vault offers biometric")

        c2.startBiometricUnlock()
        assertTrue(unlocked != null, "approved biometric must unlock")
        unlocked?.session?.close()
    }

    @Test
    fun a_denied_biometric_shows_an_error_without_unlocking() = runTest {
        val c1 = component()
        c1.submitCreate(pw()); c1.confirmRecoverySaved(); c1.enableBiometric()

        val c2 = component(authenticator = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Deny))
        c2.startBiometricUnlock()
        assertEquals(LockError.BiometricCanceled, c2.state.value.error)
    }

    @Test
    fun an_invalidated_hardware_key_forces_the_passphrase() = runTest {
        val c1 = component()
        c1.submitCreate(pw()); c1.confirmRecoverySaved(); c1.enableBiometric()

        val c2 = component(authenticator = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Invalidated))
        c2.startBiometricUnlock()

        assertEquals(LockError.HardwareInvalidated, c2.state.value.error)
        assertTrue(c2.state.value.forcePassphrase, "§3.3: invalidation forces the passphrase path")
        assertTrue(!c2.state.value.hasBiometric, "the dead hardware slot is dropped from the UI")
    }

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
