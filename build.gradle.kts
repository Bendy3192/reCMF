// Deliberately empty. Plugin versions live in settings.gradle.kts and each module
// applies only what it needs.
//
// The usual advice is to declare every plugin here with `apply false`. That is not
// workable in this build: a plugin named here is resolved on every invocation, so
// `:protocol:test` — which exists precisely so the protocol can be built and tested on
// a bare JDK — would have to reach Google's repository for the Android plugin. Naming
// only the Kotlin plugins does not work either: the Kotlin Android plugin needs the
// Android plugin on the same classloader, and it is not on this one.
//
// The cost is Gradle's "Kotlin Gradle plugin was loaded multiple times" warning, since
// :protocol and :app each load it. Both load the same version, and the modules share no
// Kotlin state, so the warning is noise here.
