plugins {
    id("darkchamber.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The camera hands each capture to VaultObjects and reads the destination's count and
            // name back from it. It touches no cipher primitive, no filesystem API and no SQLDelight
            // row type, and it does not depend on :feature:vault — core:vault is the whole surface
            // (docs/engineering/MODULES.md). The platform camera itself arrives through a seam the
            // app implements, so nothing platform-specific is declared here.
            //
            // api, not implementation: the component factory takes a VaultObjects (from the session
            // scope), so the type is part of this module's public surface.
            api(project(":core:vault"))
        }
        commonTest.dependencies {
            // A few tests save through a real VaultObjects rather than a fake: that a photo raises the
            // live count, and that a lock leaves nothing behind, are claims about the real catalog.
            // They build one the way core:vault's own tests do — a live session, a temp-dir storage,
            // an in-memory catalog — so the engine modules are on the test classpath only.
            implementation(project(":core:crypto"))
            implementation(project(":core:storage"))
            implementation(project(":core:db"))
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
