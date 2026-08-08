import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    // SDK levels, foss/store flavors, signing, git-tag version, printVersion — all live
    // in this convention plugin, which configures the app reactively (it does not apply
    // com.android.application; the alias above does).
    id("darkchamber.android.application")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

android {
    namespace = "dev.ehsanfahad.darkchamber"

    defaultConfig {
        applicationId = "dev.ehsanfahad.darkchamber"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Composition root. As features land (docs/engineering/MODULES.md), this grows into
    // the list of :feature:*:impl + :core:* modules. For now, one core wire proves it.
    implementation(project(":core:model"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}
