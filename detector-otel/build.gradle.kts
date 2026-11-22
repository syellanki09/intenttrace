plugins {
    id("intenttrace.published-library")
}

description = "Publishes IntentTrace detector findings as OpenTelemetry metrics and span events."

dependencies {
    api(project(":detector"))
    api(libs.opentelemetry.api)
    testImplementation(libs.opentelemetry.sdk.testing)
}
