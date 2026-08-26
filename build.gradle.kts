// The Kotlin plugins are declared here so both modules share one instance of them:
// :protocol applies the JVM plugin and :app the Android one, and loading the Kotlin
// plugin separately per subproject is unsupported and warns loudly.
//
// The Android and KSP plugins deliberately stay out of this block. A plugin named here
// is resolved on every build even with `apply false`, and resolving the Android plugin
// means reaching Google's repository — which a JVM-only `:protocol:test` should not have
// to do. Versions for all of them live in settings.gradle.kts.
plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
}
