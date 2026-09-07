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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BetterAlarm"

include(":app")

// `core` is a *separate* Gradle build, not a subproject.
//
// If it were a subproject of this build, Gradle's configuration phase would load
// the Android Gradle Plugin before it could run a single core test, which means
// anyone wanting to check the domain logic would first need a full Android SDK.
// As an included build, `cd core && gradle test` needs nothing but a JDK -- which
// is how every test in this repository was actually verified.
//
// Gradle substitutes the `com.alarmy:core` dependency in :app with the local
// project automatically, matching on group + name.
includeBuild("core")
