plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(17)
}

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
