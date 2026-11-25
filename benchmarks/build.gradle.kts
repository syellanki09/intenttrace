plugins {
    id("intenttrace.kotlin-conventions")
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":provenance"))
    implementation(project(":injectors"))
    implementation(project(":detector"))
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

tasks.register<JavaExec>("runEventTimeScenario") {
    workingDir(rootProject.projectDir)
    group = "application"
    description = "Run the synthetic delayed and out-of-order event demonstration"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.intenttrace.benchmarks.EventTimeMain")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
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

tasks.register<JavaExec>("runDetectorAgreement") {
    workingDir(rootProject.projectDir)
    group = "application"
    description = "Compare runtime detector findings with harness ground truth on the synthetic fixtures"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.intenttrace.benchmarks.DetectorAgreementMain")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

tasks.register<JavaExec>("runMissingContextScenario") {
    workingDir(rootProject.projectDir)
    group = "application"
    description = "Run the synthetic missing-context and recorded-degradation demonstration"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.intenttrace.benchmarks.MissingContextMain")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

tasks.register<JavaExec>("runConflictingValuesScenario") {
    workingDir(rootProject.projectDir)
    group = "application"
    description = "Run the synthetic conflicting-values and recorded-resolution demonstration"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.intenttrace.benchmarks.ConflictingValuesMain")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}
