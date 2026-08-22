package dev.ehsanfahad.darkchamber.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
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
 * Gating this cycle is **device-unlocked** (`setUnlockedDeviceRequired`, API 28+); per-use biometric
 * auth (`setUserAuthenticationRequired` + a `BiometricPrompt` `CryptoObject`) is deliberately left
 * out until the UI cycle, which has an `Activity` to present the prompt in.
 *
 * Not unit-tested in CI (no device); covered by `docs/verification/secure-keystore-manual.md` and by
 * `FakeSecureKeyStore` for the integration logic. Must compile under `assembleFossDebug`.
 */
class AndroidSecureKeyStore(
    private val alias: String = DEFAULT_ALIAS,
) : SecureKeyStore {
    override fun isAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    override fun wrap(mk: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(mk)
        return cipher.iv + ciphertext // iv is the provider-generated 12-byte GCM nonce
    }

    override fun unwrap(blob: ByteArray): Outcome<ByteArray> {
        if (blob.size <= GCM_IV_SIZE) return Outcome.Err(VaultError.Crypto.IntegrityFailure)
        return try {
            val key = loadKey() ?: return Outcome.Err(VaultError.Crypto.IntegrityFailure)
            val iv = blob.copyOfRange(0, GCM_IV_SIZE)
            val ciphertext = blob.copyOfRange(GCM_IV_SIZE, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            Outcome.Ok(cipher.doFinal(ciphertext))
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Key wiped by a device credential change / biometric enrolment — treat as key-gone.
            Outcome.Err(VaultError.Crypto.IntegrityFailure)
        } catch (_: GeneralSecurityException) {
            // GCM tag mismatch (AEADBadTagException), missing/invalid key, locked device — all map to
            // the single tamper/auth-failure outcome the contract allows (never a throw, never partial).
            Outcome.Err(VaultError.Crypto.IntegrityFailure)
        }
    }

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
            .apply {
                // Device-unlocked gating + StrongBox are both API 28+. Per-use biometric auth
                // (setUserAuthenticationRequired) is intentionally NOT set here — added with the UI
                // cycle, which can present a BiometricPrompt CryptoObject.
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
    }
}
