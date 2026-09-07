// `core` is a standalone Gradle build, not a subproject of the app.
//
// That is deliberate. It means `cd core && gradle test` needs nothing but a JDK
// — no Android SDK, no emulator, no signing config. The rules that decide
// whether you actually get out of bed can therefore be verified anywhere, and
// are verified in CI on a plain Ubuntu runner.
//
// The app pulls this in through `includeBuild("core")` in the root settings
// file, so there is still exactly one source of truth.
rootProject.name = "core"

// Lets Gradle download the JDK 17 toolchain if the machine does not already
// have one, so `gradle test` works on a fresh checkout with any modern JDK
// installed — including CI runners and whatever is on your Mac.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
