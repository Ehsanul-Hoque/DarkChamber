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
    }
}
