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
        // Host-only: the .dchm format-vector gate reads the committed JSON under docs/.
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Point the host format-vector test at the committed vectors (docs/format-test-vectors/), and
// declare that directory as a task input so editing a vector re-runs the gate (otherwise Gradle
// treats the test as up-to-date and a changed/tampered vector would be silently skipped).
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    val vectors = rootDir.resolve("docs/format-test-vectors")
    systemProperty("dchm.vectors.dir", vectors.absolutePath)
    inputs.dir(vectors).withPropertyName("dchmFormatVectors").withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}
