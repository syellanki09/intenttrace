plugins {
    kotlin("jvm") version "2.2.20"
}

group = "io.intenttrace"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)

    sourceSets {
        main {
            kotlin.srcDir("pipeline/src/main/kotlin")
        }

        test {
            kotlin.srcDir("pipeline/src/test/kotlin")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}