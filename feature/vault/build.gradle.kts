plugins {
    id("darkchamber.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The vault feature reads and writes items through VaultObjects only: import, listing
            // summaries, open, shred. It touches no cipher primitive, no filesystem API, and no
            // SQLDelight row type — core:vault is the whole surface (docs/engineering/MODULES.md).
            //
            // api, not implementation: the component factory takes a VaultObjects (from the session
            // scope), so the type is part of this module's public surface.
            api(project(":core:vault"))
        }
    }
}
