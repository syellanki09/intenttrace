plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":pipeline"))

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
