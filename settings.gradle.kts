pluginManagement {
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

val androidSdkAvailable =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (androidSdkAvailable) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found — skipping :app. Set ANDROID_HOME or create local.properties with sdk.dir.")
}
