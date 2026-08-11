plugins {
    id("darkchamber.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            // core:crypto is the ONLY module permitted to depend on the cipher primitives.
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
            // .dchm container: forward-only streaming IO + CBOR header framing (local only).
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.cbor)
        }
    }
}
