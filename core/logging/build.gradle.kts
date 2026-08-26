plugins {
    id("darkchamber.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // redactCause maps VaultError → a content-free code (hard rule #4).
            implementation(project(":core:model"))
            // Kermit only — never Crashlytics/remote logging (hard rules #3/#4).
            implementation(libs.kermit)
        }
    }
}
