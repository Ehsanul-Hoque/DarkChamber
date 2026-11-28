import dev.detekt.gradle.extensions.DetektExtension
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.accessors.dm.LibrariesForLibs

// Convention plugin for code quality (docs/engineering/MODULES.md §5). Applied by the KMP and Android
// application convention plugins, by :konsist, and by the root project, which covers the build
// scripts outside any module (its own, settings, and build-logic's).
//
// Formatting: Spotless running ktlint — the Compose rule set on Kotlin sources, plain ktlint on build
// scripts. The rules live in the root .editorconfig, so an IDE and this build read the same file.
// Static analysis: detekt's plain `detekt` task over every source set, on its default rules plus the
// departures in config/detekt/detekt.yml. Each module keeps a baseline of the findings it had when
// detekt arrived — except :core:crypto, which has none and must stay clean. A baseline entry is a rule
// and the declaration it fired in, not a line: a new finding of a rule already recorded for the same
// declaration (a second generic catch in a class that has one) passes too, so review has to see those.
//
// Only hand-written sources are in scope: build/ (generated SQLDelight code included) and the vendored
// C under src/nativeInterop never are.
//
//   ./gradlew spotlessCheck   — fails on any formatting difference or ktlint finding
//   ./gradlew spotlessApply   — fixes what ktlint can fix by itself
//   ./gradlew detekt          — fails on any detekt finding the module's baseline does not match
//
// Coverage: Kover, per module, each gated at what was measured for it — see below.

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

// Spotless passes ktlint the standard rules' settings from .editorconfig but not the Compose rule
// set's own (`compose_*`), so those are read here and handed over, keeping .editorconfig their one
// home.
val composeRuleProperties: Map<String, String> =
    providers
        .fileContents(rootProject.layout.projectDirectory.file(".editorconfig"))
        .asText
        .get()
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("compose_") && "=" in it }
        .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }

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
            ktlint(ktlintVersion)
                .setEditorConfigPath(editorConfig)
                .customRuleSets(listOf(composeRules))
                .editorConfigOverride(composeRuleProperties)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion).setEditorConfigPath(editorConfig)
        }
    }
}

// The root has no Kotlin sources of its own, so detekt goes on every other project only.
if (project != rootProject) {
    pluginManager.apply("dev.detekt")
    extensions.configure<DetektExtension> {
        // Every source set — common, platform and test — rather than the plugin's default directories.
        source.setFrom(layout.projectDirectory.dir("src"))
        config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
        // The one module whose findings are fixed rather than recorded: see the header.
        if (path != ":core:crypto") {
            baseline = layout.projectDirectory.file("detekt-baseline.xml")
        }
    }
}

// Coverage: Kover on every KMP library module but :core:testing, which is test support. Not :konsist (a
// test-only JVM module) or :androidApp (a thin host), which never apply the KMP library plugin.
//
// It measures what the JVM host tests run. Code in iosMain is never compiled for the JVM, so it is not
// in the numbers at all — the iOS simulator tests cover it, unmeasured. Generated code is excluded where
// its generator is applied (SQLDelight's, in :core:db).
//
// Each module is gated at the line coverage measured for it, rounded down to the whole percent — a
// floor that can only be raised, never a target. Raise a number here, and in the table in
// docs/engineering/TESTING.md §4, when a module's coverage rises.
// A module Kover measures but this map does not name fails the build, so none goes ungated.
//
//   ./gradlew koverVerify      — fails when a module's line coverage is below its floor
//   ./gradlew koverHtmlReport  — per module, in <module>/build/reports/kover/html
val lineCoverageFloors =
    mapOf(
        ":app" to 42,
        ":core:crypto" to 88,
        ":core:db" to 88,
        ":core:designsystem" to 81,
        ":core:logging" to 56,
        ":core:model" to 65,
        ":core:storage" to 91,
        ":core:vault" to 96,
        ":feature:camera" to 61,
        ":feature:vault" to 62,
    )

pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
    if (path != ":core:testing") {
        pluginManager.apply("org.jetbrains.kotlinx.kover")
        val floor =
            lineCoverageFloors[path]
                ?: error("$path is measured by Kover but has no line coverage floor in darkchamber.quality")
        extensions.configure<KoverProjectExtension> {
            reports {
                verify {
                    rule {
                        minBound(floor, CoverageUnit.LINE)
                    }
                }
            }
        }
    }
}
