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
