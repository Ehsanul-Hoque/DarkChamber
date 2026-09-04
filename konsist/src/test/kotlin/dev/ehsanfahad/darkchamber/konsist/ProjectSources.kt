package dev.ehsanfahad.darkchamber.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration

/**
 * The repository's **production** Kotlin sources, as Konsist sees them.
 *
 * Production is derived from the path rather than Konsist's own source-set notion because this is a
 * Kotlin Multiplatform project: sources live in `commonMain` / `androidMain` / `iosMain` (and the
 * Android launcher in plain `main`), while `commonTest` / `iosTest` / `androidHostTest` are tests.
 * Test code is deliberately out of scope — a fake or a test vector may legitimately do things
 * production code may not.
 */
internal object ProjectSources {

    private const val SRC = "/src/"

    val productionFiles: List<KoFileDeclaration> by lazy {
        Konsist.scopeFromProject()
            .files
            .filterNot { it.projectPath.contains("/build/") }
            .filter { isProductionSourceSet(it.projectPath) }
    }

    /** Every import in production, paired with the file that declares it. */
    val productionImports: List<Pair<KoFileDeclaration, String>> by lazy {
        productionFiles.flatMap { file -> file.imports.map { file to it.name } }
    }

    fun isProductionSourceSet(projectPath: String): Boolean {
        val marker = projectPath.indexOf(SRC)
        if (marker < 0) return false
        val sourceSet = projectPath.substring(marker + SRC.length).substringBefore('/')
        return sourceSet == "main" || sourceSet.endsWith("Main")
    }

    /** True when the file lives in the given Gradle module directory, e.g. `core/crypto`. */
    fun KoFileDeclaration.inModule(moduleDir: String): Boolean =
        projectPath.startsWith("/$moduleDir/")
}
