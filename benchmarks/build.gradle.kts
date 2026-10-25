plugins {
    id("intenttrace.kotlin-conventions")
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
