package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.app.di.CatalogProvider
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The fail-closed cold-open (D4/D6): `selfCheck()` create-vault + encrypted round-trip, reopen via
 * the persisted keystore (unlock path), wrong-passphrase rejection, and abort-on-first-Err. Runs
 * against a temp root + in-memory driver + [FakeSecureKeyStore] via an override Koin module — the
 * real Context/Documents/Keystore wiring is proven by the instrumented tests (TASK-008/009).
 */
class VaultBootstrapTest {
    private val testDir = Path(SystemTemporaryDirectory, "vbt-${Random.nextULong()}")
    private val vaultBase = Path(testDir, "vault")

    @AfterTest
    fun cleanup() = deleteRecursively(testDir)

    /** A bootstrap over the shared temp root, a fresh in-memory catalog, and a fake keystore. */
    private fun bootstrap(
        catalogProvider: CatalogProvider = CatalogProvider { CatalogStore.create(testSqlDriver()) },
    ): VaultBootstrap =
        VaultBootstrap(
            rootResolver = DirectoryVaultRootResolver(vaultBase),
            engine = CryptographyKotlinEngine(),
            catalogProvider = catalogProvider,
            secureKeyStore = FakeSecureKeyStore(),
        )

    @Test
    fun selfCheck_creates_the_vault_and_round_trips() {
        val outcome = bootstrap().selfCheck()

        assertTrue(outcome is Outcome.Ok, "selfCheck failed: $outcome")
        // First run wrote the keystore under the resolved root.
        assertTrue(SystemFileSystem.exists(Path(Path(vaultBase, "keys"), "mk.wrapped")), "keystore not persisted")
    }

    @Test
    fun openOrCreateVault_reopens_via_the_persisted_keystore() {
        // First open creates the vault (keystore written).
        val created = bootstrap().openOrCreateVault(PASSPHRASE())
        assertTrue(created is Outcome.Ok, "create failed: $created")
        created.value.close()

        // Second open on the same root takes the unlock path with the same passphrase.
        val reopened = bootstrap().openOrCreateVault(PASSPHRASE())
        assertTrue(reopened is Outcome.Ok, "reopen failed: $reopened")
        reopened.value.close()
    }

    @Test
    fun openOrCreateVault_rejects_a_wrong_passphrase() {
        bootstrap().openOrCreateVault(PASSPHRASE()).let {
            assertTrue(it is Outcome.Ok); it.value.close()
        }

        val wrong = bootstrap().openOrCreateVault("not-the-passphrase".encodeToByteArray())
        assertTrue(wrong is Outcome.Err, "wrong passphrase should not unlock")
        assertTrue(wrong.error is VaultError.Crypto, "wrong passphrase should be a crypto error")
    }

    @Test
    fun open_aborts_on_the_first_error_a_failing_catalog() {
        val failing = CatalogProvider { Outcome.Err(VaultError.Db(IllegalStateException("catalog down"))) }
        val outcome = bootstrap(catalogProvider = failing).openOrCreateVault(PASSPHRASE())

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
