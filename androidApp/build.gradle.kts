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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Composition root. As features land (docs/engineering/MODULES.md), this grows into
    // the list of :feature:*:impl + :core:* modules. :app carries the shared graph + bootstrap.
    implementation(project(":app"))
    implementation(project(":core:model"))
    implementation(project(":core:logging"))
    implementation(project(":core:designsystem"))
    implementation(libs.koin.android)

    // The Decompose session gate: retainedComponent (survives config changes) + the shared RootContent.
    implementation(libs.decompose)
    implementation(libs.decompose.compose)
    // MainActivity is a FragmentActivity (required by BiometricPrompt).
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    // Instrumented cold-open smoke: boots the real Koin graph on a
    // device/emulator. Runnable locally (./gradlew :androidApp:connectedFossDebugAndroidTest);
    // NOT wired into pr.yml yet (the quality/instrumented job is still deferred).
    // The thumbnail encoder's seam, so the video frame tests can name the type the platform module
    // binds; the encoder itself stays internal to :app.
    androidTestImplementation(project(":core:vault"))
    // The storage layer's write path, checked where the JVM's mkdirs() can really fail.
    androidTestImplementation(project(":core:storage"))
    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
