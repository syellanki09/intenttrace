plugins {
    id("intenttrace.kotlin-conventions")
}

// The detector core has no runtime dependencies beyond the Kotlin standard
// library so that host systems can adopt it without inheriting a framework.
dependencies {
    testImplementation(project(":evaluation"))
}
