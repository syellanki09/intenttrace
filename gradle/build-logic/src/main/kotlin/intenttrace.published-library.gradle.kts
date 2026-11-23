// Publishing conventions for the modules that host systems depend on.
//
// Artifacts use the GitHub-backed Maven Central namespace io.github.syellanki09
// while the Kotlin packages stay io.intenttrace.*. Publications are written to
// a local staging repository (build/central-staging at the root); a separate
// tool turns that directory into a Central Portal bundle, and uploading it is
// a deliberate maintainer step outside the build.
plugins {
    id("intenttrace.kotlin-conventions")
    `maven-publish`
    signing
    id("org.jetbrains.dokka-javadoc")
}

group = "io.github.syellanki09"

java {
    withSourcesJar()
}

// Keep generated docs independent of remote JDK and stdlib package lists, so
// release builds give the same result offline.
dokka {
    dokkaSourceSets.configureEach {
        enableJdkDocumentationLink.set(false)
        enableKotlinStdLibDocumentationLink.set(false)
    }
}

// Byte-for-byte repeatable archives make published artifacts checkable
// against a rebuild from the same source.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val javadocJar by tasks.registering(Jar::class) {
    description = "Assembles the Dokka-generated Javadoc required by Maven Central."
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationJavadoc"))
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
            artifact(javadocJar)
            artifactId = "intenttrace-${project.name}"
            pom {
                name.set("IntentTrace ${project.name}")
                description.set(provider { project.description ?: "IntentTrace ${project.name}" })
                url.set("https://github.com/syellanki09/intenttrace")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("syellanki09")
                        name.set("Srinivas Kalyan Yellanki")
                        url.set("https://github.com/syellanki09")
                    }
                }
                scm {
                    url.set("https://github.com/syellanki09/intenttrace")
                    connection.set("scm:git:https://github.com/syellanki09/intenttrace.git")
                    developerConnection.set("scm:git:ssh://git@github.com/syellanki09/intenttrace.git")
                }
                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/syellanki09/intenttrace/issues")
                }
            }
        }
    }
    repositories {
        maven {
            name = "centralStaging"
            url = uri(rootProject.layout.buildDirectory.dir("central-staging"))
        }
    }
}

// Signing runs only when a key is supplied, for example by the release
// workflow from repository secrets. Local and CI builds stay unsigned, and the
// bundle tool refuses an unsigned staging directory.
val signingKey = providers.environmentVariable("INTENTTRACE_SIGNING_KEY")
val signingPassword = providers.environmentVariable("INTENTTRACE_SIGNING_PASSWORD")
signing {
    isRequired = false
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
        sign(publishing.publications["library"])
    }
}
