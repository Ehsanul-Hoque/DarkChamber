import com.android.build.api.dsl.KotlinMultiplatformAndroidHostTestCompilation

plugins {
    id("darkchamber.kmp.feature")
}

kotlin {
    // Compose UI tests are written once, in commonTest, over a test base class whose Android side runs
    // under Robolectric — an expect/actual class, still Beta in Kotlin, opted into to keep the build
    // warning-free (as :core:db does for its driver seam).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        // Robolectric reads the merged manifest and resources, which the host test only sees with this.
        // Set on the compilation: the convention plugin has already created the host test, and it can
        // be created only once.
        compilations.withType<KotlinMultiplatformAndroidHostTestCompilation>().configureEach {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.compose.uiTest)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
            implementation(libs.androidx.compose.uiTestManifest)
            // Compose's test library brings Espresso 3.5.0, which calls an InputManager method current
            // Android no longer has; the idle wait fails on it under Robolectric. 3.7.0 does not.
            implementation(libs.androidx.espresso.core)
        }
        commonMain.dependencies {
            // The vault feature reads and writes items through VaultObjects only: import, listing
            // summaries, open, shred. It touches no cipher primitive, no filesystem API, and no
            // SQLDelight row type — core:vault is the whole surface (docs/engineering/MODULES.md).
            //
            // api, not implementation: the component factory takes a VaultObjects (from the session
            // scope), so the type is part of this module's public surface.
            api(project(":core:vault"))
            // The gallery's image loading: it draws the encrypted thumbnails. The loader is built
            // here, with its disk cache off — see VaultImageLoader for why that is not a preference.
            implementation(libs.coil.compose)
            // The text viewer's Markdown parser: text to a syntax tree, nothing more. The viewer
            // draws the tree itself, so links open only through its own confirmation and images are
            // never fetched.
            implementation(libs.jetbrains.markdown)
        }
    }
}
