plugins {
    id("intenttrace.kotlin-conventions")
}

dependencies {
    implementation(project(":pipeline"))
    testImplementation(project(":injectors"))
}
