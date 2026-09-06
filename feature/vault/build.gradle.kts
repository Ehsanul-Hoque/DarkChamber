plugins {
    id("darkchamber.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The vault feature reads and writes items through VaultObjects only: import, listing
            // summaries, open, shred. It touches no cipher primitive, no filesystem API, and no
            // SQLDelight row type — core:vault is the whole surface (docs/engineering/MODULES.md).
            implementation(project(":core:vault"))
        }
    }
}
