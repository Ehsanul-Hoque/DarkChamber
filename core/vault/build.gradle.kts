import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("darkchamber.kmp.library")
    // enc_meta is a tiny CBOR {name,mime} blob, framed with the same serialization runtime the
    // rest of the project uses (the .dchm header, the KeyStore).
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // The iOS test binaries pull SQLDelight's native driver (sqliter) transitively through core:db,
    // whose cinterop binds system sqlite3 — link it so the in-memory CatalogStore tests resolve
    // _sqlite3_* symbols (the same -lsqlite3 the app framework needs; KMP/iOS gotcha).
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.all { linkerOpts("-lsqlite3") }
    }

    sourceSets {
        commonMain.dependencies {
            // The compose-the-cores module: joins crypto (session/codec), storage (objects), and
            // db (catalog rows) into vault-object operations. It is the ONLY place all three meet
            // (MODULES.md §core / 5c spec §3.1). .dchm bytes stay opaque — no cipher primitives here.
            implementation(project(":core:model"))
            implementation(project(":core:crypto"))
            implementation(project(":core:storage"))
            implementation(project(":core:db"))
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.cbor)
        }
        commonTest.dependencies {
            // Tests build a real CatalogStore over a raw in-memory SqlDriver (the testSqlDriver
            // expect/actual below) + a temp-dir VaultStorage + a live CryptographyKotlinEngine session.
            implementation(libs.sqldelight.runtime)
        }
        // Host tests inject an in-memory JDBC driver.
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.driver.sqlite)
        }
        // iOS-sim tests inject the native in-memory driver (leaf test source sets).
        listOf("iosArm64Test", "iosSimulatorArm64Test").forEach { name ->
            getByName(name).dependencies {
                implementation(libs.sqldelight.driver.native)
            }
        }
    }
}
