package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS [SecureKeyStore] actual: stores the MasterKey as a **Keychain** generic-password item gated by
 * `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` — readable only while the device is unlocked, and
 * never migrated off this device (so it is never in a backup). The Keychain item *is* the
 * hardware-protected storage, so no separate cipher is needed; the returned blob is a small opaque
 * marker (the secret lives in the Keychain, keyed by service+account, not in the blob).
 *
 * This is the sanctioned hard-rule-#2 carve-out that may import `platform.Security` (like the Android
 * actual's Keystore/JCE) — it is key *storage*, not content crypto, and a non-exportable Keychain
 * secret cannot be handed to [CryptoEngine]. Shared across iosArm64 + iosSimulatorArm64 and works on
 * the simulator (no Secure Enclave required).
 *
 * Gating this cycle is **device-unlocked**. The methods are `suspend` and take a
 * [BiometricAuthenticator] as of cycle 5b; the per-use biometric *binding* (a `.biometryCurrentSet`
 * `SecAccessControl` on the item + an `LAContext` via `kSecUseAuthenticationContext`) is wired in
 * TASK-005 — this revision only updates the signatures and threads the parameter so the seam compiles.
 *
 * Not unit-tested in CI (no device secure hardware); covered by
 * `docs/verification/secure-keystore-manual.md` and by `FakeSecureKeyStore` for the integration
 * logic. Must compile under `compileKotlinIosSimulatorArm64` / `iosArm64`.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSecureKeyStore(
    private val service: String = DEFAULT_SERVICE,
    private val account: String = DEFAULT_ACCOUNT,
) : SecureKeyStore {
    override fun isAvailable(): Boolean = true

    // TODO(TASK-005): add a .biometryCurrentSet SecAccessControl and pass the authenticator's
    // LAContext via kSecUseAuthenticationContext so the item is released only after Face/Touch ID.
    override suspend fun wrap(mk: ByteArray, authenticator: BiometricAuthenticator): Outcome<ByteArray> {
        deleteHardwareKey() // clear any prior item so SecItemAdd cannot fail with errSecDuplicateItem
        val status = memScoped {
            val serviceRef = cfString(service)
            val accountRef = cfString(account)
            val dataRef = cfData(mk)
            val query = cfDictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to serviceRef,
                kSecAttrAccount to accountRef,
                kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                kSecValueData to dataRef,
            )
            val rc = SecItemAdd(query, null)
            CFRelease(query)
            CFRelease(serviceRef)
            CFRelease(accountRef)
            CFRelease(dataRef)
            rc
        }
        return if (status == errSecSuccess) {
            Outcome.Ok(byteArrayOf(HARDWARE_BLOB_VERSION)) // opaque marker; the secret is in the Keychain
        } else {
            Outcome.Err(VaultError.Crypto.IntegrityFailure) // no throw crosses the boundary
        }
    }

    override suspend fun unwrap(blob: ByteArray, authenticator: BiometricAuthenticator): Outcome<ByteArray> {
        if (blob.isEmpty()) return Outcome.Err(VaultError.Crypto.IntegrityFailure)
        return memScoped {
            val serviceRef = cfString(service)
            val accountRef = cfString(account)
            val query = cfDictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to serviceRef,
                kSecAttrAccount to accountRef,
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            )
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFRelease(query)
            CFRelease(serviceRef)
            CFRelease(accountRef)
            val dataPtr = result.value
            if (status != errSecSuccess || dataPtr == null) {
                if (dataPtr != null) CFRelease(dataPtr)
                Outcome.Err(VaultError.Crypto.IntegrityFailure) // item deleted / not found / device locked
            } else {
                val bytes = cfDataToBytes(dataPtr.reinterpret())
                CFRelease(dataPtr)
                Outcome.Ok(bytes)
            }
        }
    }

    override fun deleteHardwareKey() {
        memScoped {
            val serviceRef = cfString(service)
            val accountRef = cfString(account)
            val query = cfDictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to serviceRef,
                kSecAttrAccount to accountRef,
            )
            val status = SecItemDelete(query)
            CFRelease(query)
            CFRelease(serviceRef)
            CFRelease(accountRef)
            check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain SecItemDelete failed: $status" }
        }
    }

    private fun MemScope.cfString(s: String): CFStringRef =
        CFStringCreateWithCString(kCFAllocatorDefault, s, kCFStringEncodingUTF8)!!

    private fun MemScope.cfData(bytes: ByteArray): CFDataRef =
        bytes.usePinned { pinned ->
            CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), bytes.size.convert())!!
        }

    private fun cfDataToBytes(data: CFDataRef): ByteArray {
        val len = CFDataGetLength(data).toInt()
        if (len == 0) return ByteArray(0)
        val ptr = CFDataGetBytePtr(data) ?: return ByteArray(0)
        return ptr.readBytes(len)
    }

    /** Build an immutable CFDictionary from CFString keys and CFType values (retained by the dict). */
    private fun MemScope.cfDictionary(vararg entries: Pair<CFStringRef?, COpaquePointer?>): CFDictionaryRef {
        val n = entries.size
        val keys: CArrayPointer<COpaquePointerVar> = allocArray(n)
        val values: CArrayPointer<COpaquePointerVar> = allocArray(n)
        entries.forEachIndexed { i, (k, v) ->
            keys[i] = k?.reinterpret()
            values[i] = v
        }
        return CFDictionaryCreate(
            kCFAllocatorDefault,
            keys,
            values,
            n.convert(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!
    }

    companion object {
        const val DEFAULT_SERVICE = "dev.ehsanfahad.darkchamber.vault"
        const val DEFAULT_ACCOUNT = "mk.hardware"
        private const val HARDWARE_BLOB_VERSION: Byte = 1
    }
}
