plugins {
    id("darkchamber.kmp.library")
}

kotlin {
    // SQLDelight's native driver (sqliter) binds system sqlite3 via cinterop; the final native
    // binary must link it. Applied here because the driver reaches :app transitively through
    // core:db, and its linker options do not propagate across that module boundary. Covers the
    // test binaries and the 5a iOS Shared framework (TASK-006).
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.all { linkerOpts("-lsqlite3") }
    }

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
        // Bootstrap tests inject an in-memory driver into CatalogProvider (SqlDriver type).
        commonTest.dependencies {
            implementation(libs.sqldelight.runtime)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.driver.sqlite)
        }
        listOf("iosArm64Test", "iosSimulatorArm64Test").forEach { name ->
            getByName(name).dependencies {
                implementation(libs.sqldelight.driver.native)
            }
        }
    }
}
