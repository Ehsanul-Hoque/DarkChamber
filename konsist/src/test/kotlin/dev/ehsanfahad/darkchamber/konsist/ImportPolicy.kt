package dev.ehsanfahad.darkchamber.konsist

/**
 * The import denylists behind the security-invariant rules, as **pure predicates** over an import
 * name. Keeping them free of Konsist lets [ImportPolicyTest] prove that each rule actually matches
 * what it is supposed to match — and, just as importantly, that it does *not* match its lookalikes.
 * A rule that silently matches nothing is the failure mode these tests exist to prevent.
 */
internal object ImportPolicy {

    /** True when [this] is exactly [prefix] or a member of that package/class. */
    private fun String.isUnder(prefix: String): Boolean = this == prefix || startsWith("$prefix.")

    private fun String.isUnderAny(vararg prefixes: String): Boolean = prefixes.any { isUnder(it) }

    /**
     * Hard rule #2 — cipher primitives. `core:crypto` is the only module allowed to import these;
     * everything else goes through `CryptoEngine` / `DchmCodec` / `VaultSession`.
     */
    fun isCipherPrimitive(import: String): Boolean = import.isUnderAny(
        "dev.whyoleg.cryptography",
        "javax.crypto",
        "java.security",
        "org.bouncycastle",
        "platform.CoreCrypto",
        "platform.Security",
    )

    /**
     * Hard rule #3 / ADR-004 — no network, anywhere, in v1. The Apple entries are matched exactly:
     * `NSURL` itself is a legitimate *file* URL type (the iOS vault root resolver uses it), so a
     * `platform.Foundation.NSURL` prefix match would be wrong.
     */
    fun isNetwork(import: String): Boolean = import.isUnderAny(
        "java.net",
        "javax.net",
        "okhttp3",
        "retrofit2",
        "io.ktor",
    ) || import.isUnderAny(
        "platform.Foundation.NSURLSession",
        "platform.Foundation.NSURLRequest",
        "platform.Foundation.NSURLConnection",
        "platform.Network",
    )

    /** Hard rule #4 — no analytics, crash reporting or telemetry SDK, ever. */
    fun isTelemetrySdk(import: String): Boolean = import.isUnderAny(
        "com.google.firebase",
        "com.crashlytics",
        "io.sentry",
        "com.amplitude",
        "com.mixpanel",
        "com.appsflyer",
        "com.google.android.gms.analytics",
    )

    /**
     * Hard rule #1 — shared storage. `core:storage` owns the filesystem; nothing may reach media
     * collections or external storage, which are outside the app sandbox.
     *
     * The Storage Access Framework (`DocumentsContract`, `OpenableColumns`) is deliberately **not**
     * listed: it is how the user hands us a file to import, a read the user explicitly granted, and
     * the imported bytes go straight into a `.dchm` object. This rule targets the app reaching into
     * shared storage on its own.
     */
    fun isSharedStorage(import: String): Boolean = import.isUnderAny(
        "android.provider.MediaStore",
        "android.os.Environment",
    ) || import.contains("ExternalStorage")

    /**
     * Hard rule #4 — logging goes through `core:logging`, which owns the `VaultError` redaction
     * boundary. A module logging directly could print a name, a path or vault content.
     */
    fun isLogger(import: String): Boolean = import.isUnder("co.touchlab.kermit")
}
