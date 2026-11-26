plugins {
    // Declared apply-false so they load once and each subproject applies them by alias.
    // Kotlin Multiplatform and the AGP KMP library plugin are applied through the
    // build-logic convention plugins (core:* modules), so they no longer appear here.
    // com.android.application is still applied by the app module via alias, so it stays.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    // Formatting for the build scripts that belong to no module (this one, settings, build-logic's).
    id("darkchamber.quality")
}

// Points git at the tracked hooks in .githooks/, so a commit message and the commit's Kotlin doc
// comments are checked against the same rules CI applies before the commit exists
// (docs/engineering/WORKFLOW.md §1). Idempotent; run once per clone. CI does not need it — the PR
// check runs the same scripts.
tasks.register<Exec>("installGitHooks") {
    group = "setup"
    description = "Use the repository's tracked git hooks (.githooks/)."
    workingDir = rootDir
    commandLine("git", "config", "core.hooksPath", ".githooks")
    doLast {
        println("git hooks: core.hooksPath = .githooks (commit-msg checks each message, pre-commit each doc comment)")
    }
}
