package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.VaultError
import platform.Security.errSecAuthFailed
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a failed read of the hardware MK-wrap item is reported as. An item the Keychain cannot find
 * or will not authenticate is what a biometric re-enrollment leaves behind (`.biometryCurrentSet`),
 * so it takes the forced-passphrase path. A read refused because the device is locked says nothing
 * about the item, and is a retry that names no cause.
 */
class KeychainReadFailureTest {

    @Test
    fun a_read_refused_while_the_device_is_locked_is_a_retry() {
        assertEquals(VaultError.Auth.Failed, keychainReadFailure(errSecInteractionNotAllowed))
    }

    @Test
    fun a_missing_or_unauthenticated_item_is_the_re_enrollment_path() {
        assertEquals(VaultError.Crypto.IntegrityFailure, keychainReadFailure(errSecItemNotFound))
        assertEquals(VaultError.Crypto.IntegrityFailure, keychainReadFailure(errSecAuthFailed))
    }
}
