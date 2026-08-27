/**
 * How far the run counter had got before the repository was recreated.
 *
 * Deleting a repository resets GITHUB_RUN_NUMBER to 1, and Android refuses to install a
 * version code lower than the one already on the phone. Without this, every phone running
 * a build from the old repository would be stuck: the only way forward would be an
 * uninstall, which is exactly the settings loss the fixed signing key was meant to end.
 *
 * Raise it, never lower it.
 */
val VERSION_CODE_OFFSET = 200

/**
 * The build's own number, which has to rise for Android to treat one APK as an update to
 * another. GitHub Actions counts runs; a local build is always the offset plus one, which
 * is fine because a local build is never something a phone is asked to upgrade to.
 *
 * Deliberately not the commit count: CI clones with fetch-depth 1, so a commit count read
 * there would be 1 on every build.
 */
val buildNumber: Int =
    VERSION_CODE_OFFSET + ((System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1)

/** Bumped by hand when something is worth calling a new version. */
val RELEASE_NAME = "0.2"

/**
 * The password of the checked-in fallback key. Not a secret and not treated as one: the
 * keystore it opens is in the repository, so hiding its password would protect nothing.
 */
val FALLBACK_KEY_SECRET = "recmfdebug"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.recmf"
    // Android 17's platform is only published to the preview channel, so 36 is the
    // newest that installs from a plain SDK setup. Material 3 Expressive is unaffected:
    // it ships in compose-material3, not in the platform.
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.recmf"
        minSdk = 31
        targetSdk = 36
        versionCode = buildNumber
        versionName = "$RELEASE_NAME.$buildNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A fixed key, so each build installs over the last one and the app keeps its
        // settings, its paired watch and its data. Without one, every machine generates
        // its own and Android refuses one build as an update to another — the only way in
        // is uninstall, which takes all of that with it.
        //
        // The checked-in key is a fallback so that a fresh clone builds and installs with
        // no setup. It is public, and a public signing key means anyone can build an APK
        // Android will accept as an update to reCMF. A private key in RECMF_KEYSTORE
        // takes precedence; see the README for how to set one up.
        getByName("debug") {
            val provided = System.getenv("RECMF_KEYSTORE")?.takeIf { it.isNotBlank() }

            storeFile = provided?.let(::file) ?: file("recmf-debug.keystore")
            storePassword = System.getenv("RECMF_KEYSTORE_PASSWORD") ?: FALLBACK_KEY_SECRET
            keyAlias = System.getenv("RECMF_KEY_ALIAS") ?: "recmf"
            keyPassword = System.getenv("RECMF_KEY_PASSWORD") ?: FALLBACK_KEY_SECRET
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // So the app can say which build it is. With a dozen sideloaded APKs in a day,
        // "which one is installed?" is otherwise unanswerable from the phone.
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    lint {
        warningsAsErrors = true
        disable += setOf(
            // targetSdk cannot be raised: the Android 17 platform is not published to
            // any installable SDK channel. See the compileSdk comment above.
            "OldTargetApi",
            // enableOnBackInvokedCallback is declared on purpose. It takes effect from
            // API 33 and is ignored on 31 and 32, which is the intended behaviour.
            "UnusedAttribute",
            // Dependency versions are pinned deliberately, not left to drift.
            "GradleDependency",
            "NewerVersionAvailable",
        )
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)

    // Android provides org.json, but its unit-test stub throws on every call. This is
    // the real implementation, for tests only — it must not reach the APK, where it
    // would collide with the platform's.
    testImplementation(libs.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
