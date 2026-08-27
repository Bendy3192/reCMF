/**
 * The build's own number, which has to rise for Android to treat one APK as an update to
 * another. GitHub Actions counts runs; a local build is always 1, which is fine because a
 * local build is never something a phone is asked to upgrade to.
 *
 * Deliberately not the commit count: CI clones with fetch-depth 1, so a commit count read
 * there would be 1 on every build.
 */
val buildNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

/** Bumped by hand when something is worth calling a new version. */
val RELEASE_NAME = "0.2"

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
        // Checked into the repository on purpose. Without a fixed key, every CI runner
        // generates its own debug keystore, every build is signed by a different one, and
        // Android refuses to install one over another — so updating meant uninstalling,
        // and uninstalling took the goals, the paired watch and every setting with it.
        //
        // What it costs: anyone with this file can build an APK Android will accept as an
        // update to reCMF. For a sideloaded personal app installed only from its own CI
        // that is a fair trade for being able to update at all. Moving the keystore into
        // an Actions secret is a small change if that stops being true.
        getByName("debug") {
            storeFile = file("recmf-debug.keystore")
            storePassword = "recmfdebug"
            keyAlias = "recmf"
            keyPassword = "recmfdebug"
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
