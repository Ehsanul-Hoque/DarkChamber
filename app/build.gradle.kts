plugins {
    id("darkchamber.kmp.library")
}

kotlin {
    // The iOS umbrella framework (D1/D7): one static "Shared" framework the iosApp links.
    // export(...) the modules whose public types cross into Swift via :app's API — core:model
    // (Outcome/VaultError), core:crypto (VaultSession/CryptoEngine/SecureKeyStore), core:db
    // (CatalogStore via CatalogProvider). Exported deps must be api() (below). core:storage /
    // core:logging stay internal to :app's public surface, so they are not exported.
    // linkerOpts: SQLDelight's native driver (sqliter) binds system sqlite3 via cinterop and the
    // driver reaches :app transitively through core:db, whose linker options do not propagate
    // across the module boundary — so link it on every native binary (tests + framework).
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            export(project(":core:model"))
            export(project(":core:crypto"))
            export(project(":core:db"))
        }
        target.binaries.all { linkerOpts("-lsqlite3") }
    }

    sourceSets {
        commonMain.dependencies {
            // Composition-root home: depends on every engine module wired in cycles 2–4
            // plus the graduated logging module. No Compose this cycle (5a has no UI).
            // api(...) for the three modules exported into the iOS Shared framework.
            api(project(":core:model"))
            api(project(":core:crypto"))
            api(project(":core:db"))
            implementation(project(":core:storage"))
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
