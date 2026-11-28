plugins {
    id("darkchamber.kmp.library")
    // Single consumer of SQLDelight → apply the plugin inline, no convention plugin.
    alias(libs.plugins.sqldelight)
}

kotlin {
    // The DriverFactory production seam is an expect/actual class — still Beta in Kotlin;
    // opt in intentionally to keep the build warning-free.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            // Catalog runtime + reactive extensions. NO core:crypto — the catalog stores
            // opaque encrypted blobs + plaintext index columns; field encryption composes above.
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.sqldelight.primitive.adapters)
            implementation(libs.kotlinx.coroutines.core)
        }
        // Production driver seam (DriverFactory) — creation supplied by the app module, compile-gated
        // here. AndroidSqliteDriver needs a Context; native driver needs a DB path.
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
        }
        // Native driver on the leaf iOS main source sets; the shared actual lives in src/iosMain.
        listOf("iosArm64Main", "iosSimulatorArm64Main").forEach { name ->
            getByName(name).dependencies {
                implementation(libs.sqldelight.driver.native)
            }
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
            // .sq CREATE statements against it — drift detection for the first schema.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

// Coverage counts hand-written code only. SQLDelight generates into the package above: a class per table
// (and its Adapter), a Queries class per .sq file with its query classes, a class per custom result
// shape, the database's companion, and the implementation in `.db`. A new table, .sq file or result
// shape adds a name here.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "dev.ehsanfahad.darkchamber.core.db.db.*",
                    "dev.ehsanfahad.darkchamber.core.db.DarkChamberDatabase\$Companion",
                    "dev.ehsanfahad.darkchamber.core.db.*Queries",
                    "dev.ehsanfahad.darkchamber.core.db.*Queries\$*",
                    "dev.ehsanfahad.darkchamber.core.db.Albums",
                    "dev.ehsanfahad.darkchamber.core.db.Albums\$Adapter",
                    "dev.ehsanfahad.darkchamber.core.db.Items",
                    "dev.ehsanfahad.darkchamber.core.db.Items\$Adapter",
                    "dev.ehsanfahad.darkchamber.core.db.Audit_log",
                    "dev.ehsanfahad.darkchamber.core.db.SelectAlbumsWithCounts",
                )
            }
        }
    }
}
