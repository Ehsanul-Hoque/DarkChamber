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
            // Path/root types for VaultRootResolver + VaultStorage(root) — kotlinx-io is core:storage's
            // API type. :app holds Path values only; every filesystem op stays in core:storage.
            implementation(libs.kotlinx.io.core)
        }
        // Android launcher threads the Context via androidContext() (D2); koin-android
        // carries that seam. sqldelight-runtime: the platform Koin module builds the real
        // driver (DriverFactory.create()) and passes it to CatalogStore.create (SqlDriver type).
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.sqldelight.runtime)
        }
        // iOS platform module builds the native driver + opens the catalog (SqlDriver type).
        listOf("iosArm64Main", "iosSimulatorArm64Main").forEach { name ->
            getByName(name).dependencies {
                implementation(libs.sqldelight.runtime)
            }
        }
    }
}
