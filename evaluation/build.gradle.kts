plugins {
    id("intenttrace.kotlin-conventions")
}

dependencies {
    api(project(":pipeline"))
    testImplementation(project(":injectors"))
}
