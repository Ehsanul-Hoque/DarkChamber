plugins {
    id("darkchamber.kmp.compose")
}

kotlin {
    // The iOS umbrella framework: one static "Shared" framework the iosApp links.
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
            // Composition-root home: depends on every engine module (model/crypto/storage/db)
            // plus the graduated logging module.
            // api(...) for the three modules exported into the iOS Shared framework.
            api(project(":core:model"))
            api(project(":core:crypto"))
            api(project(":core:db"))
            implementation(project(":core:storage"))
            implementation(project(":core:vault"))
            implementation(project(":core:logging"))
            // First shared UI: the session gate lives here. Compose comes via the theme module.
            implementation(project(":core:designsystem"))
            // The vault feature: the gallery child of the unlocked subtree. The app is the only
            // module that may depend on a feature (docs/engineering/MODULES.md §1).
            implementation(project(":feature:vault"))
            implementation(libs.koin.core)
            // The gallery's image loader is session-scoped, so the composition root builds it and
            // the session's teardown empties it — :app needs Coil's ImageLoader/PlatformContext
            // types for that, not for any loading of its own.
            implementation(libs.coil.compose)
            // The Decompose session gate (RootComponent: Locked | Unlocked) + its Compose integration
            // and the Essenty lifecycle that drives background-lock.
            implementation(libs.decompose)
            implementation(libs.decompose.compose)
            implementation(libs.essenty.lifecycle)
            // LockedComponent intents launch create/unlock on a lifecycle-scoped CoroutineScope.
            implementation(libs.kotlinx.coroutines.core)
            // Path/root types for VaultRootResolver + VaultStorage(root) — kotlinx-io is core:storage's
            // API type. :app holds Path values only; every filesystem op stays in core:storage.
            implementation(libs.kotlinx.io.core)
        }
        // Android launcher threads the Context via androidContext(); koin-android
        // carries that seam. sqldelight-runtime: the platform Koin module builds the real
        // driver (DriverFactory.create()) and passes it to CatalogStore.create (SqlDriver type).
        androidMain.dependencies {
            implementation(libs.koin.android)
            implementation(libs.sqldelight.runtime)
            // The Android biometric authenticator actual: BiometricPrompt (needs a FragmentActivity)
            // + the main executor. :app only *presents* the CryptoObject — no cipher primitive here.
            implementation(libs.androidx.biometric)
            implementation(libs.androidx.fragment.ktx)
            // Dispatchers.Main for the session-gate component intents on Android.
            implementation(libs.kotlinx.coroutines.android)
        }
        // iOS platform module builds the native driver + opens the catalog (SqlDriver type).
        listOf("iosArm64Main", "iosSimulatorArm64Main").forEach { name ->
            getByName(name).dependencies {
                implementation(libs.sqldelight.runtime)
            }
        }
        // Bootstrap tests inject an in-memory driver into CatalogProvider (SqlDriver type);
        // the biometric unlock paths are suspend, so runTest drives them.
        commonTest.dependencies {
            implementation(libs.sqldelight.runtime)
            implementation(libs.kotlinx.coroutines.test)
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
