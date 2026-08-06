import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Convention plugin for every core:* module (docs/engineering/MODULES.md §5): a
// Kotlin Multiplatform library targeting Android + iOS. Keeps per-module build
// files down to `plugins { id("darkchamber.kmp.library") }` plus their deps.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

// The shared version catalog isn't auto-wired into precompiled script plugins;
// pull it in explicitly so versions still come from one source.
val libs = the<LibrariesForLibs>()

kotlin {
    // Android target via the AGP KMP library plugin (same DSL the old :shared used).
    android {
        // Namespace derived from the module path, e.g. :core:model → …core.model.
        namespace = "dev.ehsanfahad.darkchamber." +
            path.removePrefix(":").replace(":", ".").replace("-", "")
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }

        // Run commonTest on the JVM host (fast unit tests: ./gradlew …testAndroidHostTest).
        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
