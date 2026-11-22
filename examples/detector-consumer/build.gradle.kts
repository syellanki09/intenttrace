plugins {
    kotlin("jvm") version "2.2.20"
    application
}

// The IntentTrace version to consume; defaults to the version this example documents.
val intenttraceVersion = providers.gradleProperty("intenttraceVersion").getOrElse("0.1.0-preview.2")

dependencies {
    implementation("io.github.syellanki09:intenttrace-detector-otel:$intenttraceVersion")
    implementation("io.opentelemetry:opentelemetry-sdk:1.68.0")
    testImplementation(kotlin("test"))
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.68.0")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("example.RecommendationServiceKt")
}

tasks.test {
    useJUnitPlatform()
}
