package dev.ehsanfahad.darkchamber.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricPrompt
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.flatMap
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
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
 * Per-use biometric binding: the AndroidKeyStore key is created with
 * `setUserAuthenticationRequired(true)`, so a `Cipher` initialised under it can only `doFinal` after
 * the user authenticates *that Cipher instance* via a `BiometricPrompt.CryptoObject`. This actual
 * builds the `Cipher`, wraps it in a `CryptoObject`, hands **that** to [BiometricAuthenticator] as the
 * opaque `cryptoHandle`, and only finalises on the **authorised CryptoObject's Cipher** the
 * authenticator returns — so Face/Touch ID is bound to the key operation, not merely shown in front
 * of it. Whoever builds the `CryptoObject` must touch both `javax.crypto` and `androidx.biometric`;
 * keeping that here (the sanctioned hard-rule-#2 carve-out) is what keeps `javax.crypto` out of `:app`,
 * whose authenticator only *presents* the `CryptoObject` and never sees a `Cipher`. This file is the
 * carve-out that may import `java.security`/`javax.crypto` (+ the `androidx.biometric` `CryptoObject`
 * wrapper — not a cipher primitive); [CryptoEngine] and everything else stay cryptography-kotlin only.
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
        // init can throw KeyPermanentlyInvalidatedException (re-enrolled biometrics) — key-gone; any
        // other refusal is a retry (see keyUseFailure).
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        } catch (e: GeneralSecurityException) {
            return Outcome.Err(keyUseFailure(e))
        }
        // Authenticate THIS CryptoObject; only its authorised Cipher can doFinal (never a throw out).
        return authenticator.authenticate(request(BiometricPrompt.CryptoObject(cipher))).flatMap { auth ->
            val authorized = (auth.handle as? BiometricPrompt.CryptoObject)?.cipher
                ?: return@flatMap Outcome.Err(VaultError.Crypto.IntegrityFailure)
            try {
                Outcome.Ok(authorized.iv + authorized.doFinal(mk)) // iv is the 12-byte GCM nonce
            } catch (e: GeneralSecurityException) {
                Outcome.Err(keyUseFailure(e))
            }
        }
    }

    override suspend fun unwrap(blob: ByteArray, authenticator: BiometricAuthenticator): Outcome<ByteArray> {
        if (blob.size <= GCM_IV_SIZE) return Outcome.Err(VaultError.Crypto.IntegrityFailure)
        val cipher = try {
            val key = loadKey() ?: return Outcome.Err(VaultError.Crypto.IntegrityFailure)
            val iv = blob.copyOfRange(0, GCM_IV_SIZE)
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv)) }
        } catch (e: GeneralSecurityException) {
            // Key wiped by a device credential change / biometric enrolment → key-gone (§3.3);
            // anything else → a retry (see keyUseFailure).
            return Outcome.Err(keyUseFailure(e))
        }
        return authenticator.authenticate(request(BiometricPrompt.CryptoObject(cipher))).flatMap { auth ->
            val authorized = (auth.handle as? BiometricPrompt.CryptoObject)?.cipher
                ?: return@flatMap Outcome.Err(VaultError.Crypto.IntegrityFailure)
            val ciphertext = blob.copyOfRange(GCM_IV_SIZE, blob.size)
            try {
                Outcome.Ok(authorized.doFinal(ciphertext))
            } catch (e: GeneralSecurityException) {
                // A GCM tag mismatch is possible tampering; a refused key (a locked device) is a retry.
                Outcome.Err(keyUseFailure(e))
            }
        }
    }

    // The prompt copy is a neutral default; the :app authenticator localises/overrides it and only
    // relies on `cryptoHandle` (the CryptoObject to authorise). Honest copy per docs/DESIGN.md §11.
    private fun request(cryptoObject: BiometricPrompt.CryptoObject): BiometricRequest = BiometricRequest(
        title = PROMPT_TITLE,
        subtitle = PROMPT_SUBTITLE,
        cancelLabel = PROMPT_CANCEL,
        cryptoHandle = cryptoObject,
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
            // Per-use biometric binding: every wrap/unwrap requires a fresh auth of the
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

/**
 * What a failure to use the hardware MK-wrap key means. Only a key the platform reports as
 * permanently invalidated (biometrics re-enrolled, credential changed) is gone, and only a GCM tag
 * that does not verify is possible tampering — both [VaultError.Crypto.IntegrityFailure], which
 * drives the forced-passphrase path. Anything else the keystore throws (a key refused just after the
 * device unlocked, a busy keystore) says nothing about the key or the blob, so it is
 * [VaultError.Auth.Failed]: a retry that names no cause.
 */
internal fun keyUseFailure(e: GeneralSecurityException): VaultError =
    when (e) {
        is KeyPermanentlyInvalidatedException, is AEADBadTagException -> VaultError.Crypto.IntegrityFailure
        else -> VaultError.Auth.Failed
    }
