# Detector consumer example

A standalone Gradle build that uses IntentTrace the way an outside team would:
only through the published Maven coordinates, with no access to this
repository's source. A small recommendation service reads profiles from a
cache, checks each read against the primary store, and guards a profile-update
stream against redelivery. Findings go to OpenTelemetry and, in the runnable
`main`, to standard output as JSON lines.

```kotlin
dependencies {
    implementation("io.github.syellanki09:intenttrace-detector-otel:0.1.0-preview.2")
}
```

`intenttrace-detector-otel` brings in `intenttrace-detector`. Use
`intenttrace-detector` alone when you do not use OpenTelemetry.

Run it against artifacts on Maven Central once they are released:

```bash
./gradlew -p examples/detector-consumer test run
```

Run it against locally built artifacts, as CI does before every release:

```bash
./gradlew publishAllPublicationsToCentralStagingRepository
./gradlew -p examples/detector-consumer test run \
  -PintenttraceRepository="$PWD/build/central-staging" \
  -PintenttraceVersion="$(sed -n 's/^version = "\(.*\)"$/\1/p' build.gradle.kts)"
```

The scenario is synthetic. It shows the integration points and the shape of
the output, not behaviour under production load.
