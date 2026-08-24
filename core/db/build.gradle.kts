plugins {
    id("darkchamber.kmp.library")
    // Single consumer of SQLDelight → apply the plugin inline (D7), no convention plugin.
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            // Catalog runtime + reactive extensions. NO core:crypto (D1) — the catalog stores
            // opaque encrypted blobs + plaintext index columns; field encryption composes above.
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.sqldelight.primitive.adapters)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
        // Host tests inject an in-memory JDBC driver (no Context / Robolectric).
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.driver.sqlite)
        }
        // iOS tests inject the native in-memory driver. Applied on the leaf test source sets
        // (created eagerly with each target); the shared actual lives in src/iosTest.
        listOf("iosArm64Test", "iosSimulatorArm64Test").forEach { name ->
            getByName(name).dependencies {
                implementation(libs.sqldelight.driver.native)
            }
        }
    }
}

sqldelight {
    databases {
        create("DarkChamberDatabase") {
            packageName.set("dev.ehsanfahad.darkchamber.core.db")
            // Commit the schema-v1 baseline dump (databases/1.db); verifyMigrations checks the
            // .sq CREATE statements against it — drift detection for the first schema (D8).
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
