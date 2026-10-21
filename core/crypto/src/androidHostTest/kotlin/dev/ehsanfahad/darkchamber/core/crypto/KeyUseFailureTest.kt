package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.VaultError
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a failure to use the hardware MK-wrap key is reported as. Only two things justify the
 * re-enrollment path or a tamper report — a key the platform says is permanently invalidated, and a
 * GCM tag that does not verify. Everything else the keystore can throw says only that the key was
 * refused this time (the device just unlocked, the keystore busy), and is a retry.
 *
 * `KeyPermanentlyInvalidatedException` is an Android class that cannot be constructed on the host;
 * its branch is an `is` check on the platform's own type.
 */
class KeyUseFailureTest {

    @Test
    fun a_tag_that_does_not_verify_is_possible_tampering() {
        assertEquals(VaultError.Crypto.IntegrityFailure, keyUseFailure(AEADBadTagException()))
    }

    @Test
    fun any_other_refusal_is_a_retry_that_claims_no_cause() {
        for (refusal in listOf(
            InvalidKeyException(),
            KeyStoreException(),
            UnrecoverableKeyException(),
            IllegalBlockSizeException(),
        )) {
            assertEquals(VaultError.Auth.Failed, keyUseFailure(refusal), refusal::class.simpleName)
        }
    }
}
