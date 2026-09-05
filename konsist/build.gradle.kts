plugins {
    alias(libs.plugins.kotlinJvm)
}

// No jvmToolchain: this module compiles with whatever JDK runs Gradle (17 locally, 21 on CI),
// matching the rest of the repo, which pins jvmTarget rather than a toolchain. Pinning a toolchain
// here would make CI demand a JDK it does not have.

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
}

tasks.test {
    useJUnit()
    // Konsist reads the repository from disk, so the task's inputs are not the module's own
    // sources: a cached "up to date" would let a violation in another module slip through CI.
    outputs.upToDateWhen { false }
}
