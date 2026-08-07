rootProject.name = "DarkChamber"

pluginManagement {
    // Convention plugins (docs/engineering/MODULES.md §5) — compiled by the
    // included build, applied by id from each module's build.gradle.kts.
    includeBuild("build-logic")

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
}

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
    }
}

include(":androidApp")
include(":shared")
include(":core:model")
include(":core:crypto")
include(":core:storage")
include(":core:db")
include(":core:designsystem")
include(":core:logging")
include(":core:testing")