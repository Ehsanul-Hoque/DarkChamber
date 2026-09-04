package dev.ehsanfahad.darkchamber.konsist

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
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
