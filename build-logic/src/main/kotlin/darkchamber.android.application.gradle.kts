import com.android.build.api.dsl.ApplicationExtension
import org.gradle.accessors.dm.LibrariesForLibs
import java.util.Properties

// Convention plugin for the Android application module (docs/engineering/MODULES.md §5,
// RELEASE.md §3/§9). Holds everything that isn't app-identity: SDK levels, the
// foss/store flavor axis, dependency-info stripping, conditional signing, the
// Git-tag-derived version, and the printVersion task. The app module keeps only its
// namespace/applicationId, Kotlin/Compose wiring, and dependency list.
//
// Extracted verbatim from androidApp/build.gradle.kts — no behavior change.

plugins {
    id("com.android.application")
}

val libs = the<LibrariesForLibs>()

// ---------------------------------------------------------------------------
// local.properties (present on dev machines, absent on CI — both are fine)
// ---------------------------------------------------------------------------
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
/** Returns a local.properties value, or null if missing/blank. */
fun local(key: String): String? = localProps.getProperty(key)?.takeIf { it.isNotBlank() }

// ---------------------------------------------------------------------------
// Version resolution (see docs/engineering/RELEASE.md §3)
//
// The version is NOT stored in a file — Git tags are the single source of truth.
//   • CI passes -PversionName / -PversionCode, computed by scripts/compute-version.sh
//     from the commits since the last tag. These always win.
//   • Local builds derive the version from the nearest Git tag.
//   • With no tags at all, fall back to 0.0.0.
//
// versionCode = major * 100_000 + minor * 1_000 + patch   → 1.4.2 becomes 104002
// ---------------------------------------------------------------------------

/** Nearest tag reachable from HEAD, e.g. "v0.2.0". Empty when the repo has no tags. */
val gitTag: String = providers.exec {
    commandLine("git", "describe", "--tags", "--abbrev=0")
    isIgnoreExitValue = true // no tags yet (or no git) must not fail the build
}.standardOutput.asText.getOrElse("").trim()

/** Maps a SemVer string to a monotonic integer Play Store versionCode. */
fun semVerToVersionCode(semVer: String): Int {
    val parts = semVer.substringBefore('-').split('.') // tolerate 0.0.0-branch-sha test builds
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    require(major <= 99 && minor <= 99 && patch <= 999) {
        "Version $semVer exceeds the width convention (major<=99, minor<=99, patch<=999)"
    }
    return major * 100_000 + minor * 1_000 + patch
}

val appVersionName: String =
    (findProperty("versionName") as String?)          // CI (compute-version.sh / test builds)
        ?: gitTag.removePrefix("v").ifBlank { null }  // local: nearest tag
        ?: "0.0.0"                                    // no tags yet

val appVersionCode: Int =
    ((findProperty("versionCode") as String?)?.toIntOrNull()
        ?: semVerToVersionCode(appVersionName))
        .coerceAtLeast(1) // Play requires versionCode >= 1; 0.0.0 would otherwise be 0

configure<ApplicationExtension> {
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()

        versionCode = appVersionCode
        versionName = appVersionName
    }

    // -----------------------------------------------------------------------
    // Distribution flavors (docs/engineering/RELEASE.md §9)
    //   • foss  — F-Droid / GitHub builds; Phase 3 sync ships UNGATED here.
    //   • store — Play Store build; Phase 3 adds Play Billing in this flavor only.
    // Identical today by design — establishing the axis now keeps the paths clean.
    // -----------------------------------------------------------------------
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") { dimension = "distribution" }
        create("store") { dimension = "distribution" }
    }

    // Don't embed Google's encrypted dependency-info block: F-Droid flags it as an
    // anti-feature and it contradicts the verify-it-yourself + reproducible-builds goal.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // -----------------------------------------------------------------------
    // Signing (all conditional on local.properties — see RELEASE.md §9)
    //   • Debug: optional shared dev keystore when configured; else AGP's default.
    //   • Release (local): prod keystore if present, else dev (prod keys aren't on
    //     dev machines, so this resolves to the dev key locally).
    //   • Release (CI): local.properties absent → no release signingConfig here;
    //     signing comes from -Pandroid.injected.signing.* (build-release-apk.sh).
    // -----------------------------------------------------------------------
    signingConfigs {
        local("keystore.dev.path")?.let { devPath ->
            getByName("debug") {
                storeFile = rootProject.file(devPath)
                storePassword = local("keystore.dev.password")
                keyAlias = local("key.dev.alias")
                keyPassword = local("key.dev.password")
            }
        }

        (local("keystore.prod.path") ?: local("keystore.dev.path"))?.let { relPath ->
            create("release") {
                storeFile = rootProject.file(relPath)
                storePassword = local("keystore.prod.password") ?: local("keystore.dev.password")
                keyAlias = local("key.prod.alias") ?: local("key.dev.alias")
                keyPassword = local("key.prod.password") ?: local("key.dev.password")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            // Null on CI (no local release config) → AGP uses -Pandroid.injected.signing.*
            // Non-null locally → signs with the resolved prod/dev keystore.
            signingConfig = signingConfigs.findByName("release")

            // TODO: enable R8 before the first public release (its own PR; keep rules
            //  for Compose/Koin/SQLDelight/serialization/cryptography-kotlin; must stay
            //  deterministic for reproducible builds — RELEASE.md §7).
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // `debug` picks up the (possibly overridden) debug signingConfig automatically.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Prints the resolved version — handy locally and for CI logs:
//   ./gradlew :androidApp:printVersion
tasks.register("printVersion") {
    description = "Prints the resolved versionName and versionCode"
    val name = appVersionName
    val code = appVersionCode
    doLast { println("versionName=$name versionCode=$code") }
}
