import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("darkchamber.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // iOS Argon2id: compile the vendored phc-winner-argon2 reference sources
    // into a per-arch libargon2.a from committed C (no prebuilt blob), then
    // bundle it into the `argon2` cinterop klib. The iosMain actual calls argon2id_hash_raw.
    val argon2Dir = layout.projectDirectory.dir("src/nativeInterop/argon2")
    targets.withType<KotlinNativeTarget>().configureEach {
        val isSim = konanTarget.name.contains("simulator", ignoreCase = true)
        val sdk = if (isSim) "iphonesimulator" else "iphoneos"
        val triple = if (isSim) "arm64-apple-ios13.0-simulator" else "arm64-apple-ios13.0"
        val libDir = layout.buildDirectory.dir("argon2/$targetName")

        val buildArgon2 = tasks.register<Exec>("buildArgon2$targetName") {
            group = "build"
            description = "Compile vendored phc-winner-argon2 into libargon2.a for $targetName"
            inputs.dir(argon2Dir).withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.dir(libDir)
            executable = argon2Dir.file("build-argon2.sh").asFile.absolutePath
            args(sdk, triple, libDir.get().asFile.absolutePath)
        }

        compilations.getByName("main").cinterops.create("argon2") {
            defFile(project.file("src/nativeInterop/cinterop/argon2.def"))
            includeDirs(argon2Dir.dir("include"))
            // -staticLibrary (from the .def) is resolved against this per-arch path and
            // embedded into the klib, so downstream test/framework links pick it up.
            extraOpts("-libraryPath", libDir.get().asFile.absolutePath)
        }
        val targetCap = targetName.replaceFirstChar { it.uppercase() }
        tasks.named("cinteropArgon2$targetCap") { dependsOn(buildArgon2) }
    }

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
        // Argon2id: pure-JVM BouncyCastle actual — host-testable. iOS uses the vendored
        // phc-winner-argon2 cinterop instead (no Argon2 runtime dependency on that side).
        androidMain.dependencies {
            implementation(libs.bouncycastle.bcprov)
            // The BiometricPrompt.CryptoObject wrapper that binds Face/Touch ID to the auth-required
            // Keystore Cipher. Confined to SecureKeyStore.android.kt (the hard-rule-#2
            // carve-out) so building the CryptoObject — which needs both javax.crypto and the
            // biometric wrapper — keeps javax.crypto out of :app. Not a cipher primitive.
            implementation(libs.androidx.biometric)
        }
        // Auth-aware seams are suspend — runTest drives the coroutine unlock paths.
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
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
