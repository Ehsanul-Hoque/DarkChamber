plugins {
    // Declared apply-false so they load once and each subproject applies them by alias.
    // Kotlin Multiplatform and the AGP KMP library plugin are applied through the
    // build-logic convention plugins (core:* modules), so they no longer appear here.
    // com.android.application is still applied by the app module via alias, so it stays.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
}

// Points git at the tracked hooks in .githooks/, so a commit message is checked against the same
// rule CI applies before the commit exists (docs/engineering/WORKFLOW.md §1). Idempotent; run once
// per clone. CI does not need it — the PR check runs the same script over the PR's range.
tasks.register<Exec>("installGitHooks") {
    group = "setup"
    description = "Use the repository's tracked git hooks (.githooks/)."
    workingDir = rootDir
    commandLine("git", "config", "core.hooksPath", ".githooks")
    doLast { println("git hooks: core.hooksPath = .githooks (commit-msg checks each message)") }
}
