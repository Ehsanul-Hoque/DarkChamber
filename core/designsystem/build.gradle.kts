plugins {
    id("darkchamber.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The design system's public surface is Compose: DarkChamberTheme + Aperture.
            // Feature UI consumes these roles (never raw hex, docs/DESIGN.md).
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(compose.material3)
        }
    }
}
