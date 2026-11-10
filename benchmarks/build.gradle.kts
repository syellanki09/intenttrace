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

tasks.register<JavaExec>("runReplayScenario") {
    workingDir(rootProject.projectDir)
    group = "application"
    description = "Run the synthetic duplicate-replay and idempotency demonstration"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.intenttrace.benchmarks.DuplicateReplayMain")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
}

tasks.register<JavaExec>("runContractScenario") {
    workingDir(rootProject.projectDir)
    group = "application"
    description = "Run the synthetic cross-client duration contract demonstration"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.intenttrace.benchmarks.ContractUnitDriftMain")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}
