import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

// Convention plugin for every :feature:* module (docs/engineering/MODULES.md §5): a Compose-bearing
// KMP library plus the presentation stack every feature shares — Decompose components, Koin, the
// design system, and the logging boundary. Keeps a feature build file to
// `plugins { id("darkchamber.kmp.feature") }` plus the feature's own dependencies.
//
// Features get Compose transitively: :core:designsystem re-exports the Compose artifacts as `api`,
// so no feature declares them itself and none can drift onto a different Compose version.

plugins {
    id("darkchamber.kmp.compose")
}

val libs = the<LibrariesForLibs>()

kotlin {
    // Features reach the catalog transitively (feature -> core:vault -> core:db), whose native
    // driver binds system sqlite3 via cinterop. Without this the iOS test/framework binaries fail to
    // resolve _sqlite3_* at link time — the KMP/iOS gotcha that also bites :core:db and :core:vault.
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.all { linkerOpts("-lsqlite3") }
    }

    sourceSets {
        commonMain.dependencies {
            // Domain types (Outcome / VaultError / VaultItemKind) and the theme roles every screen
            // reads instead of raw hex (docs/DESIGN.md).
            implementation(project(":core:model"))
            implementation(project(":core:designsystem"))
            // The only sanctioned way for a feature to log: core:logging redacts VaultError to a
            // content-free code (hard rule #4). Kermit is confined to that module.
            implementation(project(":core:logging"))
            // Presentation: a feature's screen is a Decompose component holding Value<State>
            // (docs/engineering/PATTERNS.md §3). Koin is here for features that own a module; a
            // session-scoped feature exports a factory instead and may not use it (§1).
            implementation(libs.decompose)
            implementation(libs.decompose.compose)
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}
