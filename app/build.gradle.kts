plugins {
    id("darkchamber.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Composition-root home: depends on every engine module wired in cycles 2–4
            // plus the graduated logging module. No Compose this cycle (5a has no UI).
            implementation(project(":core:model"))
            implementation(project(":core:crypto"))
            implementation(project(":core:storage"))
            implementation(project(":core:db"))
            implementation(project(":core:logging"))
            implementation(libs.koin.core)
        }
        // Android launcher threads the Context via androidContext() (D2); koin-android
        // carries that seam. Platform Koin modules land in TASK-004.
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
    }
}
