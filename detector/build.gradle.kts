plugins {
    id("intenttrace.published-library")
}

description = "Dependency-free runtime checks that detect stale, missing, duplicated, conflicting and late context in decision paths."

// The detector core has no runtime dependencies beyond the Kotlin standard
// library so that host systems can adopt it without inheriting a framework.
dependencies {
    testImplementation(project(":evaluation"))
}
