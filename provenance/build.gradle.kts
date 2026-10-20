plugins {
    id("intenttrace.kotlin-conventions")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":evaluation"))
    api(libs.kotlinx.serialization.json)
    testImplementation(project(":injectors"))
}
