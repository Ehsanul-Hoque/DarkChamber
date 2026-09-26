package dev.ehsanfahad.darkchamber.konsist

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import dev.ehsanfahad.darkchamber.konsist.ProjectSources.codeOf
import dev.ehsanfahad.darkchamber.konsist.ProjectSources.inModule
import dev.ehsanfahad.darkchamber.konsist.ProjectSources.productionFiles
import dev.ehsanfahad.darkchamber.konsist.ProjectSources.productionImports
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The **security-invariant** architecture rules (CLAUDE.md hard rules #1–#4, docs/THREAT_MODEL.md).
 * These make the invariants self-policing: violating "all crypto goes through `CryptoEngine`" or
 * "no network in v1" fails the build instead of relying on a reviewer noticing an import.
 *
 * Layer/naming rules are deliberately absent — they land with the rest of the quality tooling.
 */
class SecurityArchitectureTest {

    @Test
    fun `cipher primitives are imported only inside core-crypto`() {
        assertNoForbiddenImports(
            rule = "cipher primitives (hard rule #2)",
            forbidden = ImportPolicy::isCipherPrimitive,
            allowedIn = { it.inModule("core/crypto") },
            remedy = "Go through CryptoEngine / DchmCodec / VaultSession instead.",
        )
    }

    @Test
    fun `no module imports a networking API`() {
        assertNoForbiddenImports(
            rule = "networking APIs (hard rule #3 / ADR-004)",
            forbidden = ImportPolicy::isNetwork,
            remedy = "v1 ships without the INTERNET permission and has no network code at all.",
        )
    }

    @Test
    fun `no module imports an analytics or crash-reporting SDK`() {
        assertNoForbiddenImports(
            rule = "analytics / crash SDKs (hard rule #4)",
            forbidden = ImportPolicy::isTelemetrySdk,
            remedy = "No trackers, ever. Diagnostics are local, opt-in and user-exported.",
        )
    }

    @Test
    fun `shared-storage APIs are touched only inside core-storage`() {
        assertNoForbiddenImports(
            rule = "shared-storage APIs (hard rule #1)",
            forbidden = ImportPolicy::isSharedStorage,
            allowedIn = { it.inModule("core/storage") },
            remedy = "Plaintext never touches shared storage; core:storage owns the filesystem.",
        )
    }

    @Test
    fun `the logger is imported only inside core-logging`() {
        assertNoForbiddenImports(
            rule = "logger (hard rule #4)",
            forbidden = ImportPolicy::isLogger,
            allowedIn = { it.inModule("core/logging") },
            remedy = "Log via core:logging, which redacts VaultError to a content-free code.",
        )
    }

    @Test
    fun `every image loader is built without a disk cache`() {
        // An image loader in this app is only ever handed decrypted vault content, and a disk cache
        // stores what it fetched — so one left on writes plaintext previews into the app's cache
        // directory, where they survive a lock, the scratch purge and the process. Hard rule #1, and
        // invisible: every tile renders correctly and no test that draws a grid would notice.
        //
        // Coil attaches one by default (measured — a bare builder hands back a RealDiskCache), so
        // this is a rule about what a future loader must remember, not about the one that exists.
        // It is a source rule rather than a unit test because a test can only cover the loader it
        // knows about, and the hazard is precisely the loader nobody told the tests about.
        val violations = productionFiles
            .filter { BUILDS_AN_IMAGE_LOADER.containsMatchIn(codeOf(it)) }
            .filterNot { file -> DISK_CACHE_OFF.any { file.text.contains(it) } }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine(
                    "An ImageLoader is built without disabling its disk cache (hard rule #1). " +
                        "Call vaultImageLoader, or disable it with diskCache(null) and " +
                        "diskCachePolicy(CachePolicy.DISABLED).",
                )
                violations.forEach { appendLine("  ${it.projectPath}") }
            },
        )
    }

    @Test
    fun `no Android source declares a PlatformContext in the graph`() {
        // On Android `coil3.PlatformContext` is a typealias for `android.content.Context`, so a
        // `single<PlatformContext> { androidContext() }` is a definition keyed on Context whose body
        // asks Koin for a Context — itself. It also overrides the application context registered at
        // startup, so the *first* thing in the graph to want a context recurses until the stack ends.
        // A fresh install crashed on launch before the gate drew anything, and the graph offers no
        // clean error to read: just a StackOverflowError inside Koin's own resolver.
        //
        // The right answer is no binding at all — the application context already is a
        // PlatformContext. A source rule because it cannot be unit-tested: producing the failure
        // needs a real Context, and the failure is a stack overflow rather than a return value.
        val violations = productionFiles
            .filter { it.projectPath.contains("/src/androidMain/") }
            .filter { DECLARES_A_PLATFORM_CONTEXT.containsMatchIn(codeOf(it)) }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine(
                    "An Android source declares PlatformContext in the Koin graph. On Android that " +
                        "type is android.content.Context, so the definition resolves to itself and " +
                        "the graph recurses. Remove it: androidContext(app) already provides one.",
                )
                violations.forEach { appendLine("  ${it.projectPath}") }
            },
        )
    }

    private fun assertNoForbiddenImports(
        rule: String,
        forbidden: (String) -> Boolean,
        allowedIn: (KoFileDeclaration) -> Boolean = { false },
        remedy: String,
    ) {
        val violations = productionImports
            .filterNot { (file, _) -> allowedIn(file) }
            .filter { (_, import) -> forbidden(import) }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("Forbidden $rule found in production sources. $remedy")
                violations.forEach { (file, import) -> appendLine("  ${file.projectPath}: $import") }
            },
        )
    }

    private companion object {
        /**
         * Both ways Coil hands back a loader: the builder, and the one-argument factory
         * `ImageLoader(context)` — which is `ImageLoader.Builder(context).build()`, defaults and
         * all, so it reintroduces the disk cache in a single line that mentions no builder.
         *
         * The leading boundary is what keeps `vaultImageLoader(…)` — whose name ends in the same
         * characters — from matching every call site of the one loader that is already safe.
         */
        val BUILDS_AN_IMAGE_LOADER = Regex("""(?<![A-Za-z0-9_])ImageLoader(\.Builder)?\(""")

        // Either is sufficient on its own; the loader that exists does both, and a file doing one
        // of them has clearly made the decision rather than inherited it.
        val DISK_CACHE_OFF = listOf("diskCache(null)", "CachePolicy.DISABLED")

        /**
         * Any Koin declaration of `PlatformContext` — `single`, `factory` or `scoped`, named or not,
         * and written either bare or fully qualified as `coil3.PlatformContext`. A *named* one would
         * not recurse, but it is still a second `Context` definition beside the real one and the
         * reason to want one has not come up.
         *
         * The optional package prefix is here because the first version of this regex missed
         * `single<coil3.PlatformContext>` — checked by planting both spellings, which is the only
         * way a text rule is ever actually verified.
         */
        val DECLARES_A_PLATFORM_CONTEXT =
            Regex("""(single|factory|scoped)\s*(\([^)]*\))?\s*<\s*([A-Za-z0-9_]+\.)*PlatformContext\s*>""")
    }
}

/**
 * Guards the guard. Every rule above is an *absence* assertion, so a scope that resolves to zero
 * files — a moved directory, a Konsist upgrade changing path handling, a broken filter — would make
 * all five pass while checking nothing. These assertions fail loudly in that case.
 */
class ScanCoverageTest {

    @Test
    fun `the production scan reaches every module that holds Kotlin sources`() {
        val modules = listOf(
            "app", "androidApp",
            "core/model", "core/crypto", "core/storage", "core/db",
            "core/vault", "core/designsystem", "core/logging",
            // Every new feature module belongs here: the rules are absence assertions, so a module
            // the scan never reaches is a module the security invariants do not cover.
            "feature/vault",
        )
        val missing = modules.filter { module -> productionFiles.none { it.inModule(module) } }

        assertTrue(missing.isEmpty(), "Konsist scanned no production file in: $missing")
    }

    @Test
    fun `the production scan sees a meaningful number of imports`() {
        assertTrue(
            productionImports.size > 100,
            "Only ${productionImports.size} production imports scanned — the scope looks broken.",
        )
    }

    @Test
    fun `test sources are excluded from the production scan`() {
        val testFiles = productionFiles.filter { file ->
            file.projectPath.substringAfter("/src/").substringBefore('/').contains("Test")
        }

        assertTrue(testFiles.isEmpty(), "Test sources leaked into the production scan: $testFiles")
    }
}
