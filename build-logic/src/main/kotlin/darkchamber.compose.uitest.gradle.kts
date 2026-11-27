import com.android.build.api.dsl.KotlinMultiplatformAndroidHostTestCompilation
import org.gradle.accessors.dm.LibrariesForLibs

// Convention plugin for a module with Compose UI tests, applied beside its darkchamber.kmp.* plugin by
// each module that has them. The tests are written once, in commonTest, as subclasses of :core:testing's
// UiTest: on the Android host they run under Robolectric, on the iOS simulator natively. The module's
// other host tests stay plain JUnit — only UiTest's subclasses pay Robolectric's start-up.

plugins {
    id("darkchamber.kmp.compose")
}

val libs = the<LibrariesForLibs>()
val isCoreTesting = path == ":core:testing"

kotlin {
    android {
        // Robolectric reads the merged manifest and resources, which the host test sees only with this.
        // Set on the compilation: darkchamber.kmp.library has already created the host test, and it can
        // be created only once.
        compilations.withType<KotlinMultiplatformAndroidHostTestCompilation>().configureEach {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonTest.dependencies {
            // UiTest, the shared finders and Compose's test API (which :core:testing exports).
            if (!isCoreTesting) implementation(project(":core:testing"))
        }
        getByName("androidHostTest").dependencies {
            // The activity Compose's Android test rule launches.
            implementation(libs.androidx.compose.uiTestManifest)
            // Compose's test library brings Espresso 3.5.0, which calls an InputManager method current
            // Android no longer has; the idle wait fails on it under Robolectric. 3.7.0 does not.
            implementation(libs.androidx.espresso.core)
        }
        // runComposeUiTest and its receiver are still marked experimental; every test would opt in.
        matching { it.name.endsWith("Test") }.configureEach {
            languageSettings.optIn("androidx.compose.ui.test.ExperimentalTestApi")
        }
    }
}
