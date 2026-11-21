plugins {
    id("intenttrace.kotlin-conventions")
}

dependencies {
    api(project(":detector"))
    api(libs.opentelemetry.api)
    testImplementation(libs.opentelemetry.sdk.testing)
}
