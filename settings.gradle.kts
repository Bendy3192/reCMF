pluginManagement {
    // Plugin versions live here rather than in the version catalog, and the modules
    // apply them without a version. Declaring them in the root build script instead —
    // even with `apply false` — resolves every one of them on every build, so a JVM-only
    // build would still have to reach Google's repository for the Android plugin.
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.21"
        id("org.jetbrains.kotlin.android") version "2.3.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
        // Not 9.x: AGP 9 brings its own Kotlin support and refuses to have the
        // Kotlin Android plugin applied alongside it, which this build needs for the
        // Compose plugin.
        id("com.android.application") version "8.13.0"
        id("com.google.devtools.ksp") version "2.3.11"
    }

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "reCMF"

// :protocol is a plain Kotlin/JVM module and builds anywhere, including CI images
// without an Android SDK. :app is only wired in when an SDK is actually available,
// so `./gradlew :protocol:test` stays runnable on a bare JDK.
include(":protocol")

// Blank counts as absent: CI blanks these to build the JVM module in isolation on a
// runner image that sets them regardless.
val androidSdkAvailable =
    !System.getenv("ANDROID_HOME").isNullOrBlank() ||
        !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkAvailable) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found — skipping :app. Set ANDROID_HOME or create local.properties with sdk.dir.")
}
