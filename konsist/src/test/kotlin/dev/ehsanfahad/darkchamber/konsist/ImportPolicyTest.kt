package dev.ehsanfahad.darkchamber.konsist

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves each denylist in [ImportPolicy] actually **bites** — and that it stops at the right edge.
 * A rule that matches nothing passes every absence assertion in [SecurityArchitectureTest] while
 * enforcing nothing, so these cases are the real guarantee that the rules work.
 */
class ImportPolicyTest {

    @Test
    fun `cipher primitives are matched, including wildcards and nested members`() {
        listOf(
            "dev.whyoleg.cryptography.algorithms.AES",
            "javax.crypto.Cipher",
            "javax.crypto.spec.GCMParameterSpec",
            "javax.crypto.*",
            "java.security.SecureRandom",
            "org.bouncycastle.crypto.generators.Argon2BytesGenerator",
            "platform.CoreCrypto.CC_SHA256",
            "platform.Security.kSecClass",
        ).forEach { assertTrue(ImportPolicy.isCipherPrimitive(it), "should be forbidden: $it") }
    }

    @Test
    fun `cipher rule does not match unrelated packages with the same start`() {
        listOf(
            "kotlin.random.Random",
            "platform.SecurityFoundation.Something",
            "dev.whyolegextra.cryptography.Nope",
            "dev.ehsanfahad.darkchamber.core.crypto.CryptoEngine",
        ).forEach { assertFalse(ImportPolicy.isCipherPrimitive(it), "should be allowed: $it") }
    }

    @Test
    fun `networking APIs are matched on both platforms`() {
        listOf(
            "java.net.URL",
            "java.net.HttpURLConnection",
            "javax.net.ssl.HttpsURLConnection",
            "okhttp3.OkHttpClient",
            "retrofit2.Retrofit",
            "io.ktor.client.HttpClient",
            "platform.Foundation.NSURLSession",
            "platform.Foundation.NSURLRequest",
            "platform.Network.nw_connection_t",
        ).forEach { assertTrue(ImportPolicy.isNetwork(it), "should be forbidden: $it") }
    }

    @Test
    fun `network rule allows the file-URL types the iOS vault root resolver needs`() {
        // NSURL is a *file* URL here; a prefix match on platform.Foundation.NSURL would break
        // VaultRootResolver.ios.kt, which uses NSURL + NSURLIsExcludedFromBackupKey on the vault dir.
        listOf(
            "platform.Foundation.NSURL",
            "platform.Foundation.NSURLIsExcludedFromBackupKey",
            "platform.Foundation.NSFileManager",
            "platform.Foundation.NSDocumentDirectory",
        ).forEach { assertFalse(ImportPolicy.isNetwork(it), "should be allowed: $it") }
    }

    @Test
    fun `telemetry SDKs are matched`() {
        listOf(
            "com.google.firebase.crashlytics.FirebaseCrashlytics",
            "com.crashlytics.android.Crashlytics",
            "io.sentry.Sentry",
            "com.google.android.gms.analytics.Tracker",
        ).forEach { assertTrue(ImportPolicy.isTelemetrySdk(it), "should be forbidden: $it") }
    }

    @Test
    fun `shared-storage APIs are matched`() {
        listOf(
            "android.provider.MediaStore",
            "android.provider.MediaStore.Images",
            "android.os.Environment",
            "androidx.core.content.getExternalStorageDirectory",
        ).forEach { assertTrue(ImportPolicy.isSharedStorage(it), "should be forbidden: $it") }
    }

    @Test
    fun `shared-storage rule allows app-private filesystem access`() {
        listOf(
            "kotlinx.io.files.Path",
            "kotlinx.io.files.SystemFileSystem",
            "android.content.Context",
            // The Storage Access Framework is how the user hands us a file to import.
            "android.provider.DocumentsContract",
            "android.provider.OpenableColumns",
        ).forEach { assertFalse(ImportPolicy.isSharedStorage(it), "should be allowed: $it") }
    }

    @Test
    fun `the logger rule matches kermit only`() {
        assertTrue(ImportPolicy.isLogger("co.touchlab.kermit.Logger"))
        assertFalse(ImportPolicy.isLogger("dev.ehsanfahad.darkchamber.core.logging.logVaultError"))
    }
}
