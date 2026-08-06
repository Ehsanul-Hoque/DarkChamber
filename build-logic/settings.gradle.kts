// build-logic — an included ("composite") build that hosts DarkChamber's convention
// plugins (docs/engineering/MODULES.md §5). Kept separate from the main build so the
// plugins compile once and every module applies them by id.

rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    // Share the single source of truth for versions with the main build, so the
    // convention plugins (added in TASK-002 / REFACTOR-001) resolve plugin and
    // library versions from the same catalog the modules use.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
