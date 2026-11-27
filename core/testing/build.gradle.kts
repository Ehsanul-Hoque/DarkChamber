plugins {
    id("darkchamber.kmp.compose")
    // Its own helpers are tested the way they are used: in Compose UI tests.
    id("darkchamber.compose.uitest")
}

kotlin {
    // UiTest is an expect/actual class (Robolectric's runner on Android) — still Beta in Kotlin; opted
    // into to keep the build warning-free, as :core:db does.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            // The helpers are written against Compose's test API, and every UI test is written in it.
            api(libs.compose.uiTest)
            // Content under test is drawn in the app's theme.
            implementation(project(":core:designsystem"))
        }
        androidMain.dependencies {
            // UiTest's Android side names Robolectric's runner in JUnit's @RunWith (Robolectric brings
            // JUnit at run time only). This module is only ever a test dependency, so nothing of it
            // reaches the app.
            implementation(libs.robolectric)
            implementation(libs.junit)
        }
    }
}
