// A standalone build that uses IntentTrace only through its published Maven
// coordinates, the way an outside team would. It is not part of the main build.
//
// By default artifacts come from Maven Central. To check artifacts before they
// are released, point it at a local staging repository:
//   ./gradlew -p examples/detector-consumer test -PintenttraceRepository=$PWD/build/central-staging

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "intenttrace-detector-consumer"

dependencyResolutionManagement {
    repositories {
        providers.gradleProperty("intenttraceRepository").orNull?.let { staging ->
            maven {
                name = "intenttraceStaging"
                url = uri(staging)
                content { includeGroup("io.github.syellanki09") }
            }
        }
        mavenCentral()
    }
}
