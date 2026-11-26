import org.gradle.accessors.dm.LibrariesForLibs

// Convention plugin for code quality (docs/engineering/MODULES.md §5): formatting through Spotless
// running ktlint — with the Compose rule set on Kotlin sources, plain ktlint on build scripts. Applied by the KMP and Android application convention
// plugins, by :konsist, and by the root project, which covers the build scripts outside any module
// (its own, settings, and build-logic's).
//
// The rules themselves live in the root .editorconfig, so an IDE and this build read the same
// file. Only hand-written sources are in scope: build/ (generated SQLDelight code included) and the
// vendored C under src/nativeInterop never are.
//
//   ./gradlew spotlessCheck   — fails on any formatting difference or rule finding
//   ./gradlew spotlessApply   — fixes what ktlint can fix by itself

plugins {
    id("com.diffplug.spotless")
}

val libs = the<LibrariesForLibs>()

val ktlintVersion = libs.versions.ktlint.get()
val composeRules =
    libs.compose.rules.ktlint
        .get()
        .let { "${it.module}:${it.versionConstraint.requiredVersion}" }
val editorConfig = rootProject.file(".editorconfig")

spotless {
    if (project == rootProject) {
        // The root has no sources of its own; it owns the scripts no module does.
        kotlinGradle {
            target("*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
            targetExclude("build-logic/build/**")
            ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
        }
    } else {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/build/**", "src/nativeInterop/**")
            ktlint(ktlintVersion).setEditorConfigPath(editorConfig).customRuleSets(listOf(composeRules))
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
        }
    }
}
