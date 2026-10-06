plugins {
    kotlin("jvm") version "2.2.20" apply false
}

group = "io.intenttrace"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}
