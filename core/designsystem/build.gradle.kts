plugins {
    id("darkchamber.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The design system's public surface is Compose: DarkChamberTheme + Aperture.
            // Feature UI consumes these roles (never raw hex, docs/DESIGN.md).
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.material3)
        }
    }
}
