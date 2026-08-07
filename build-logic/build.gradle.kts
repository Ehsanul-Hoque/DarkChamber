import org.gradle.api.provider.Provider
import org.gradle.plugin.use.PluginDependency

plugins {
    // Enables authoring Gradle convention plugins as precompiled `.gradle.kts`
    // scripts under src/main/kotlin.
    `kotlin-dsl`
}

// Put the Gradle plugins that the convention plugins *apply by id* on the compile
// classpath. A precompiled script plugin can only `id("…")`-apply a plugin whose
// implementation is visible here. Versions come from the shared catalog (`libs`),
// so the single source of truth still holds inside the included build.
dependencies {
    // Exposes the generated `libs` catalog accessor (LibrariesForLibs) to the
    // precompiled script plugins, so they can read versions from the catalog too.
    compileOnly(files(libs.javaClass.superclass.protectionDomain.codeSource.location))

    implementation(libs.plugins.kotlinMultiplatform.asDependency())
    implementation(libs.plugins.androidMultiplatformLibrary.asDependency())
    implementation(libs.plugins.androidApplication.asDependency())
}

/** Turns a version-catalog plugin alias into its plugin-marker artifact coordinate. */
fun Provider<PluginDependency>.asDependency(): Provider<String> = map { dep ->
    "${dep.pluginId}:${dep.pluginId}.gradle.plugin:${dep.version}"
}
