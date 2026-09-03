package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Contract test over the fake seam — pins the approve/deny/invalidated outcomes. */
class FakeBiometricAuthenticatorTest {
    private val request = BiometricRequest(title = "Unlock vault", cancelLabel = "Cancel")

    @Test
    fun approveReturnsOkAndPropagatesTheHandle() = runTest {
        val handle = Any()
        val auth = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Approve)
        val result = auth.authenticate(request.copy(cryptoHandle = handle))
        val ok = assertIs<Outcome.Ok<BiometricAuth>>(result)
        assertEquals(handle, ok.value.handle)
        assertEquals(1, auth.authenticateCount)
    }

    @Test
    fun denyReturnsAuthCancelled() = runTest {
        val auth = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Deny)
        val result = auth.authenticate(request)
        val err = assertIs<Outcome.Err>(result)
        assertEquals(VaultError.Auth.Cancelled, err.error)
    }

    @Test
    fun invalidatedReturnsIntegrityFailure() = runTest {
        val auth = FakeBiometricAuthenticator(FakeBiometricAuthenticator.Mode.Invalidated)
        val result = auth.authenticate(request)
        val err = assertIs<Outcome.Err>(result)
        assertTrue(err.error is VaultError.Crypto.IntegrityFailure)
    }
}
