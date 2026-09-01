plugins {
    id("darkchamber.kmp.library")
}

kotlin {
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
        }
    }
}
