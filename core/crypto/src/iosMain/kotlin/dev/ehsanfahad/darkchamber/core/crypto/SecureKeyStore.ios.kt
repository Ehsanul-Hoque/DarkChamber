package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.flatMap
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
import platform.Foundation.CFBridgingRetain
import platform.LocalAuthentication.LAContext
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecAccessControlRef
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecParam
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecValueData
import platform.Security.kSecAccessControlBiometryCurrentSet

/**
 * iOS [SecureKeyStore] actual: stores the MasterKey as a **Keychain** generic-password item gated by
 * `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` — readable only while the device is unlocked, and
 * never migrated off this device (so it is never in a backup). The Keychain item *is* the
 * hardware-protected storage, so no separate cipher is needed; the returned blob is a small opaque
 * marker (the secret lives in the Keychain, keyed by service+account, not in the blob).
 *
 * This is the sanctioned hard-rule-#2 carve-out that may import `platform.Security` +
 * `platform.LocalAuthentication` (like the Android actual's Keystore/JCE) — it is key *storage* and
 * device auth, not content crypto, and a non-exportable Keychain secret cannot be handed to
 * [CryptoEngine]. Shared across iosArm64 + iosSimulatorArm64 and works on the simulator (no Secure
 * Enclave required).
 *
 * Per-use biometric binding: the item carries a `SecAccessControl` created with
 * `kSecAccessControlBiometryCurrentSet`, so the Keychain releases the secret only after a Face/Touch ID
 * evaluation whose enrolled set is unchanged — re-enrolling biometrics invalidates it (→
 * [VaultError.Crypto.IntegrityFailure], the forced-passphrase-fallback trigger, §3.3). Each call first
 * authenticates through [BiometricAuthenticator], whose evaluated `LAContext` is threaded into the
 * query via `kSecUseAuthenticationContext` so add/read use that single prompt rather than a second
 * system one.
 *
 * Not unit-tested in CI (no device secure hardware); covered by
 * `docs/verification/session-gate-manual.md` and by `FakeSecureKeyStore` for the integration logic.
 * Must compile under `compileKotlinIosSimulatorArm64` / `iosArm64`.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSecureKeyStore(
    private val service: String = DEFAULT_SERVICE,
    private val account: String = DEFAULT_ACCOUNT,
) : SecureKeyStore {
    override fun isAvailable(): Boolean = true

    override suspend fun wrap(mk: ByteArray, authenticator: BiometricAuthenticator): Outcome<ByteArray> =
        authenticator.authenticate(AUTH_REQUEST).flatMap { auth ->
            val authContext = auth.handle as? LAContext
            deleteHardwareKey() // clear any prior item so SecItemAdd cannot fail with errSecDuplicateItem
            val status = memScoped {
                val serviceRef = cfString(service)
                val accountRef = cfString(account)
                val dataRef = cfData(mk)
                val access = createBiometricAccess()
                    ?: return@memScoped errSecParam // fail closed: no biometric access control
                val ctxRef = authContext?.let { CFBridgingRetain(it) }
                val entries = buildList<Pair<CFStringRef?, COpaquePointer?>> {
                    add(kSecClass to kSecClassGenericPassword)
                    add(kSecAttrService to serviceRef)
                    add(kSecAttrAccount to accountRef)
                    add(kSecAttrAccessControl to access)
                    add(kSecValueData to dataRef)
                    if (ctxRef != null) add(kSecUseAuthenticationContext to ctxRef)
                }
                val query = cfDictionary(*entries.toTypedArray())
                val rc = SecItemAdd(query, null)
                CFRelease(query)
                CFRelease(serviceRef)
                CFRelease(accountRef)
                CFRelease(dataRef)
                CFRelease(access)
                if (ctxRef != null) CFRelease(ctxRef)
                rc
            }
            if (status == errSecSuccess) {
                Outcome.Ok(byteArrayOf(HARDWARE_BLOB_VERSION)) // opaque marker; secret lives in the Keychain
            } else {
                Outcome.Err(VaultError.Crypto.IntegrityFailure) // no throw crosses the boundary
            }
        }

    override suspend fun unwrap(blob: ByteArray, authenticator: BiometricAuthenticator): Outcome<ByteArray> {
        if (blob.isEmpty()) return Outcome.Err(VaultError.Crypto.IntegrityFailure)
        return authenticator.authenticate(AUTH_REQUEST).flatMap { auth ->
            val authContext = auth.handle as? LAContext
            memScoped {
                val serviceRef = cfString(service)
                val accountRef = cfString(account)
                val ctxRef = authContext?.let { CFBridgingRetain(it) }
                val entries = buildList<Pair<CFStringRef?, COpaquePointer?>> {
                    add(kSecClass to kSecClassGenericPassword)
                    add(kSecAttrService to serviceRef)
                    add(kSecAttrAccount to accountRef)
                    add(kSecReturnData to kCFBooleanTrue)
                    add(kSecMatchLimit to kSecMatchLimitOne)
                    if (ctxRef != null) add(kSecUseAuthenticationContext to ctxRef)
                }
                val query = cfDictionary(*entries.toTypedArray())
                val result = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(query, result.ptr)
                CFRelease(query)
                CFRelease(serviceRef)
                CFRelease(accountRef)
                if (ctxRef != null) CFRelease(ctxRef)
                val dataPtr = result.value
                if (status != errSecSuccess || dataPtr == null) {
                    if (dataPtr != null) CFRelease(dataPtr)
                    // item deleted / not found / auth failed / key invalidated (re-enrolled biometrics)
                    Outcome.Err(VaultError.Crypto.IntegrityFailure)
                } else {
                    val bytes = cfDataToBytes(dataPtr.reinterpret())
                    CFRelease(dataPtr)
                    Outcome.Ok(bytes)
                }
            }
        }
    }

    /** A `.biometryCurrentSet` access control over a device-unlocked, this-device-only secret. */
    private fun createBiometricAccess(): SecAccessControlRef? =
        SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecAccessControlBiometryCurrentSet,
            null,
        )

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

        // The iOS authenticator builds/evaluates its own LAContext, so no cryptoHandle is passed in;
        // the returned LAContext (auth.handle) is threaded into the Keychain query. Honest copy (§11).
        private val AUTH_REQUEST = BiometricRequest(
            title = "Unlock DarkChamber",
            subtitle = "Confirm it's you to open your vault",
            cancelLabel = "Use passphrase",
        )
    }
}
