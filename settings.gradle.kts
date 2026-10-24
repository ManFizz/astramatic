rootProject.name = "AstraMatic"

pluginManagement {
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

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":desktopApp")
include(":shared")

// Cardboard SDK собирается из исходников (NDK + CMake) только по запросу:
//   gradlew -Pastramatic.buildCardboard=true :cardboard:publishAar
// В обычной сборке используется готовый androidApp/libs/cardboard-sdk.aar.
if (providers.gradleProperty("astramatic.buildCardboard").orNull == "true") {
    include(":cardboard")
    project(":cardboard").projectDir = file("third_party/cardboard")
}