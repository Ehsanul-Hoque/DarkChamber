package dev.ehsanfahad.darkchamber.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.flatMap
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android [SecureKeyStore] actual: wraps the MasterKey under a non-exportable AES-256-GCM key held
 * in the **AndroidKeyStore** (hardware-backed / StrongBox where present). The wrapping key never
 * leaves the TEE, so it can only be used through the platform's own `Cipher` handle — which is why
 * this file is the sanctioned hard-rule-#2 carve-out that may import `java.security` / `javax.crypto`
 * (like Argon2's BouncyCastle actual). [CryptoEngine] and everything else stay cryptography-kotlin
 * only. This is key *storage*, not content crypto.
 *
 * The stored blob is `iv ‖ ciphertext` (the GCM tag is appended to the ciphertext by the provider).
 *
 * Per-use biometric binding (cycle 5b): the AndroidKeyStore key is created with
 * `setUserAuthenticationRequired(true)`, so a `Cipher` initialised under it can only `doFinal` after
 * the user authenticates *that Cipher instance* via a `BiometricPrompt.CryptoObject`. This actual
 * builds the `Cipher`, hands it to [BiometricAuthenticator] as the opaque `cryptoHandle`, and only
 * finalises on the **authorised Cipher** the authenticator returns — so Face/Touch ID is bound to the
 * key operation, not merely shown in front of it. Keeping the `CryptoObject`/`BiometricPrompt` in the
 * `:app` authenticator (not here) is what lets `:core:crypto` avoid an `androidx.biometric` dependency;
 * this file still only imports the sanctioned `java.security`/`javax.crypto` carve-out (hard rule #2).
 *
 * A symmetric auth-required key gates ENCRYPT too, so [wrap] (enrollment) prompts once — documented.
 * Re-enrolled biometrics invalidate the key → `Cipher.init` throws `KeyPermanentlyInvalidatedException`
 * → [VaultError.Crypto.IntegrityFailure] (the forced-passphrase-fallback trigger, §3.3).
 *
 * Not unit-tested in CI (no device); covered by `docs/verification/session-gate-manual.md` and by
 * `FakeSecureKeyStore` for the integration logic. Must compile under `assembleFossDebug`.
 */
class AndroidSecureKeyStore(
    private val alias: String = DEFAULT_ALIAS,
) : SecureKeyStore {
    override fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    override suspend fun wrap(mk: ByteArray, authenticator: BiometricAuthenticator): Outcome<ByteArray> {
        // init can throw KeyPermanentlyInvalidatedException (re-enrolled biometrics) — map to key-gone.
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            return Outcome.Err(VaultError.Crypto.IntegrityFailure)
        } catch (_: GeneralSecurityException) {
            return Outcome.Err(VaultError.Crypto.IntegrityFailure)
        }
        // Authenticate THIS Cipher; only the authorised one can doFinal (never a throw across boundary).
        return authenticator.authenticate(request(cipher)).flatMap { auth ->
            val authorized = auth.handle as? Cipher ?: return@flatMap Outcome.Err(VaultError.Crypto.IntegrityFailure)
            try {
                Outcome.Ok(authorized.iv + authorized.doFinal(mk)) // iv is the 12-byte GCM nonce
            } catch (_: GeneralSecurityException) {
                Outcome.Err(VaultError.Crypto.IntegrityFailure)
            }
        }
    }

    override suspend fun unwrap(blob: ByteArray, authenticator: BiometricAuthenticator): Outcome<ByteArray> {
        if (blob.size <= GCM_IV_SIZE) return Outcome.Err(VaultError.Crypto.IntegrityFailure)
        val cipher = try {
            val key = loadKey() ?: return Outcome.Err(VaultError.Crypto.IntegrityFailure)
            val iv = blob.copyOfRange(0, GCM_IV_SIZE)
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Key wiped by a device credential change / biometric enrolment — treat as key-gone (§3.3).
            return Outcome.Err(VaultError.Crypto.IntegrityFailure)
        } catch (_: GeneralSecurityException) {
            return Outcome.Err(VaultError.Crypto.IntegrityFailure)
        }
        return authenticator.authenticate(request(cipher)).flatMap { auth ->
            val authorized = auth.handle as? Cipher ?: return@flatMap Outcome.Err(VaultError.Crypto.IntegrityFailure)
            val ciphertext = blob.copyOfRange(GCM_IV_SIZE, blob.size)
            try {
                Outcome.Ok(authorized.doFinal(ciphertext))
            } catch (_: GeneralSecurityException) {
                // GCM tag mismatch (AEADBadTagException), locked device — the single tamper/auth outcome.
                Outcome.Err(VaultError.Crypto.IntegrityFailure)
            }
        }
    }

    // The prompt copy is a neutral default; the :app authenticator localises/overrides it and only
    // relies on `cryptoHandle` (the Cipher to authorise). Honest copy per docs/DESIGN.md §11.
    private fun request(cipher: Cipher): BiometricRequest = BiometricRequest(
        title = PROMPT_TITLE,
        subtitle = PROMPT_SUBTITLE,
        cancelLabel = PROMPT_CANCEL,
        cryptoHandle = cipher,
    )

    override fun deleteHardwareKey() {
        val store = androidKeyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias) // idempotent — no-op if absent
    }

    private fun getOrCreateKey(): SecretKey = loadKey() ?: generateKey()

    private fun loadKey(): SecretKey? = androidKeyStore().getKey(alias, null) as? SecretKey

    private fun generateKey(): SecretKey {
        // StrongBox where the device has it; fall back to the TEE-backed keystore otherwise.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return buildKey(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // device has no usable StrongBox — fall through
            }
        }
        return buildKey(strongBox = false)
    }

    private fun buildKey(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Per-use biometric binding (cycle 5b): every wrap/unwrap requires a fresh auth of the
            // Cipher via a CryptoObject. setInvalidatedByBiometricEnrollment is true by default for
            // auth-required keys, so re-enrolling biometrics invalidates the key (→ IntegrityFailure).
            .setUserAuthenticationRequired(true)
            .apply {
                // Device-unlocked gating + StrongBox are both API 28+.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setUnlockedDeviceRequired(true)
                    if (strongBox) setIsStrongBoxBacked(true)
                }
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        /** Default AndroidKeyStore alias for the vault's hardware MK-wrap key. */
        const val DEFAULT_ALIAS = "darkchamber.mk.hardware"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_BITS = 128
        private const val PROMPT_TITLE = "Unlock DarkChamber"
        private const val PROMPT_SUBTITLE = "Confirm it's you to open your vault"
        private const val PROMPT_CANCEL = "Use passphrase"
    }
}
