// Convention plugin for the Compose-bearing modules (:core:designsystem, :app):
// a darkchamber.kmp.library plus Compose Multiplatform + the Kotlin Compose
// compiler, so every Compose module shares one setup. Keeps per-module build
// files to `plugins { id("darkchamber.kmp.compose") }` plus their Compose deps.

plugins {
    id("darkchamber.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
