package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.app.di.CatalogProvider
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The fail-closed cold-open (D6): create-vault (keystore persisted), reopen via the unlock path,
 * wrong-passphrase rejection, biometric enroll + unlock (approve/deny), and abort-on-first-Err.
 * Runs against a temp root + in-memory driver + [FakeSecureKeyStore] + [FakeBiometricAuthenticator];
 * the real Context/Documents/Keystore wiring is proven by the platform hosts (TASK-011/012).
 */
class VaultBootstrapTest {
    private val testDir = Path(SystemTemporaryDirectory, "vbt-${Random.nextULong()}")
    private val vaultBase = Path(testDir, "vault")

    // One keystore per test instance — the real platform key persists across app launches, so every
    // bootstrap() over the same root shares it (a fresh instance would model a wiped hardware key).
    private val sks = FakeSecureKeyStore()

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    /** A bootstrap over the shared temp root, a fresh in-memory catalog, and the shared fake keystore. */
    private fun bootstrap(
        catalogProvider: CatalogProvider = CatalogProvider { CatalogStore.create(testSqlDriver()) },
    ): VaultBootstrap =
        VaultBootstrap(
            rootResolver = DirectoryVaultRootResolver(vaultBase),
            engine = CryptographyKotlinEngine(),
            catalogProvider = catalogProvider,
            secureKeyStore = sks,
        )

    private fun approve() = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Approve)

    @Test
    fun createVault_persists_the_keystore_and_returns_a_recovery_key() {
        val created = bootstrap().createVault(PASSPHRASE())
        assertTrue(created is Outcome.Ok, "create failed: $created")
        assertTrue(created.value.recoveryKey.isNotBlank(), "recovery key must be shown")
        created.value.vault.session.close()
        assertTrue(SystemFileSystem.exists(Path(Path(vaultBase, "keys"), "mk.wrapped")), "keystore not persisted")
    }

    @Test
    fun create_then_unlock_via_passphrase() {
        bootstrap().createVault(PASSPHRASE()).let {
            assertTrue(it is Outcome.Ok, "create failed: $it"); it.value.vault.session.close()
        }
        val reopened = bootstrap().unlock(PASSPHRASE())
        assertTrue(reopened is Outcome.Ok, "reopen failed: $reopened")
        reopened.value.session.close()
    }

    @Test
    fun unlock_rejects_a_wrong_passphrase() {
        bootstrap().createVault(PASSPHRASE()).let {
            assertTrue(it is Outcome.Ok); it.value.vault.session.close()
        }
        val wrong = bootstrap().unlock("not-the-passphrase".encodeToByteArray())
        assertTrue(wrong is Outcome.Err, "wrong passphrase should not unlock")
        assertTrue(wrong.error is VaultError.Crypto, "wrong passphrase should be a crypto error")
    }

    @Test
    fun create_enroll_then_unlock_with_biometric_approve() = runTest {
        val created = bootstrap().createVault(PASSPHRASE())
        assertTrue(created is Outcome.Ok)
        val boot = bootstrap()
        // enroll on a fresh boot over the same root (reads the persisted keystore)
        val unlocked = boot.unlock(PASSPHRASE())
        assertTrue(unlocked is Outcome.Ok)
        assertTrue(boot.enrollBiometric(unlocked.value, approve()) is Outcome.Ok, "enroll failed")
        unlocked.value.session.close()

        val viaBio = bootstrap().unlockWithBiometric(approve())
        assertTrue(viaBio is Outcome.Ok, "biometric unlock failed: $viaBio")
        viaBio.value.session.close()
        created.value.vault.session.close()
    }

    @Test
    fun unlock_with_biometric_denied_is_an_auth_error() = runTest {
        val created = bootstrap().createVault(PASSPHRASE())
        assertTrue(created is Outcome.Ok)
        val boot = bootstrap()
        val unlocked = boot.unlock(PASSPHRASE())
        assertTrue(unlocked is Outcome.Ok)
        assertTrue(boot.enrollBiometric(unlocked.value, approve()) is Outcome.Ok)
        unlocked.value.session.close()

        val deny = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Deny)
        val viaBio = bootstrap().unlockWithBiometric(deny)
        assertTrue(viaBio is Outcome.Err, "a denied prompt must not unlock")
        assertTrue(viaBio.error is VaultError.Auth, "a denied prompt is an Auth error")
        created.value.vault.session.close()
    }

    @Test
    fun unlock_with_biometric_without_enrollment_is_malformed() = runTest {
        bootstrap().createVault(PASSPHRASE()).let {
            assertTrue(it is Outcome.Ok); it.value.vault.session.close()
        }
        // No hardware slot enrolled — the hardware path has nothing to open.
        val viaBio = bootstrap().unlockWithBiometric(approve())
        assertTrue(viaBio is Outcome.Err)
        assertTrue(viaBio.error is VaultError.Crypto.MalformedContainer)
    }

    @Test
    fun createVault_aborts_on_the_first_error_a_failing_catalog() {
        val failing = CatalogProvider { Outcome.Err(VaultError.Db(IllegalStateException("catalog down"))) }
        val outcome = bootstrap(catalogProvider = failing).createVault(PASSPHRASE())

        assertTrue(outcome is Outcome.Err, "a failing catalog must abort the open")
        assertTrue(outcome.error is VaultError.Db)
        // Aborted before any keystore was written — no half-open vault.
        assertTrue(!SystemFileSystem.exists(Path(Path(vaultBase, "keys"), "mk.wrapped")), "half-open vault left a keystore")
    }

    private fun PASSPHRASE(): ByteArray = "correct-horse-battery".encodeToByteArray()

    private fun deleteRecursively(path: Path) {
        val md = SystemFileSystem.metadataOrNull(path) ?: return
        if (md.isDirectory) for (child in SystemFileSystem.list(path)) deleteRecursively(child)
        SystemFileSystem.delete(path, mustExist = false)
    }
}
