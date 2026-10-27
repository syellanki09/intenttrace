plugins {
    id("intenttrace.kotlin-conventions")
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":provenance"))
    implementation(project(":injectors"))
}

application {
    mainClass.set("io.intenttrace.benchmarks.StaleContextScenarioMainKt")
}

tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
}
