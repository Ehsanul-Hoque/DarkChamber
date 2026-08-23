plugins {
    id("darkchamber.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            // The ONLY module allowed to touch filesystem APIs (MODULES.md §core).
            // NOT core:crypto — .dchm bytes flow through storage as opaque source/sink (spec D6).
            implementation(libs.kotlinx.io.core)
        }
    }
}
