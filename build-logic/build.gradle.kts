plugins {
    // Enables authoring Gradle convention plugins as precompiled `.gradle.kts`
    // scripts under src/main/kotlin.
    `kotlin-dsl`
}

// The plugin dependencies the convention plugins compile against (Kotlin
// Multiplatform, Android Gradle Plugin, Compose) are declared in TASK-002 /
// REFACTOR-001, when the first plugin that needs them is authored. This scaffold
// intentionally ships no plugins yet — only the composite build that will hold them.
