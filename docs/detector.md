# Runtime Detector

**Status:** experimental, unreleased. The API may change before a stable release.

The benchmark harness knows ground truth because it controls every input. A
production system does not. The `detector` module is the part of IntentTrace a
host system runs itself: it reports information-integrity findings at runtime
from evidence the host already has, and the `detector-otel` module publishes
those findings through OpenTelemetry.

The core module depends only on the Kotlin standard library.

## What it checks

| Check | Host calls | Reports | Confidence |
| --- | --- | --- | --- |
| `FreshnessCheck` with an oracle | `checkDecision` with the version the decision read | `stale-context` when the authoritative latest eligible version is more than `maxVersionLag` newer; `missing-context` when the decision read nothing although context was eligible; `conflicting-values` when the read is newer than the oracle | Confirmed, except the disagreement case |
| `FreshnessCheck` with `maxAge` | `checkDecision` | `stale-context` when no oracle verdict is available and the read is older than `maxAge` | Suspected |
| `ReplayGuard` | `onDelivery` for every delivery, with a payload fingerprint | `duplicate-replay` for a repeated identity and payload; `conflicting-values` for a repeated identity with a different payload. Returns whether to apply | Confirmed |
| `EventTimeMonitor` | `onArrival`, `onApplied`, and decisions through `checkDecision` | `stale-context` when an older event replaced newer state; `delayed-events` when an event that occurred before a recorded decision arrived after it | Confirmed |

Contract incompatibility has no runtime check yet.

## Integration example

```kotlin
val detector = IntentTraceDetector(
    sinks = listOf(OpenTelemetryFindingSink(openTelemetry)),
    freshness = FreshnessCheck(oracle = { key, at -> primary.latestVersion(key, at) }),
    replay = ReplayGuard(retention = Duration.ofHours(1)),
    eventTime = EventTimeMonitor()
)

// In the decision path, after reading context from a cache or replica:
detector.checkDecision(ContextRead(requestId, userId, now, VersionStamp(read.version, read.committedAt)))

// In the event consumer:
val delivery = detector.onDelivery(userId, event.id, event.payloadSha256)
detector.onArrival(userId, event.id, event.occurredAt)
if (delivery.shouldApply) {
    try {
        state.apply(event)
    } catch (e: Exception) {
        detector.onApplicationFailed(delivery) // so the retry is applied
        throw e
    }
    detector.onApplied(userId, event.id, event.occurredAt)
}
```

`ExampleIntegrationTest` in `detector-otel` keeps the first part of this example
compiling and checks that a stale read appears on the request span.

## OpenTelemetry output

- Metric `intenttrace.findings` (counter, unit `{finding}`) with only
  `intenttrace.failure_class` and `intenttrace.confidence` as attributes, so
  cardinality stays bounded.
- When a span is current, an `intenttrace.finding` span event with the context
  key, decision and event identifiers, explanation, and evidence under
  `intenttrace.evidence.*`.

These names are IntentTrace's own. They are not OpenTelemetry semantic conventions.

`JsonLinesFindingSink` writes the same findings as deterministic JSONL (schema
version 1) for systems without OpenTelemetry.

## Agreement with ground truth

`./gradlew :benchmarks:runDetectorAgreement --no-daemon` runs the detector over
the existing stale-context, duplicate-replay and event-time fixtures, giving it
only what a host would see, and writes one record per case to
`benchmarks/build/results/detector-agreement/results.jsonl`.

| Family | Case | Ground truth | Detector | Outcome |
| --- | --- | --- | --- | --- |
| stale-context | control | SATISFIED | none | true negative |
| stale-context | injected | VIOLATED | stale-context | true positive |
| duplicate-replay | control | SATISFIED | none | true negative |
| duplicate-replay | naive-replay | VIOLATED | duplicate-replay | true positive |
| duplicate-replay | idempotent-replay | SATISFIED | duplicate-replay | detected and prevented |
| event-time-ordering | control | SATISFIED | none | true negative |
| event-time-ordering | reordered-arrival | VIOLATED | stale-context | true positive |
| event-time-ordering | event-time-reconciliation | SATISFIED | none | true negative |
| event-time-ordering | late-arrival | VIOLATED | delayed-events | true positive |

These are nine fixed synthetic cases. They show the checks behave as designed;
they do not measure precision, recall, or overhead on real workloads. The
contract unit-drift cases are not scored because no contract check exists.

The [system benchmark](system-benchmark.md) measures cost and detection in a
running Kafka and Redis data path under a synthetic workload, including the
trade-off between inline and off-path decision checks.

The existing scenario records keep `detected = null`. Their schemas are
unchanged, and agreement is reported separately.

## Limits

- Freshness detection is only as good as the oracle. An oracle that lags the
  truth produces false negatives; an oracle that reads uncommitted data can
  produce disagreement findings.
- Versions must be positive and increase with every commit.
- `ReplayGuard` records an identity when it returns `APPLY`. A host that fails
  to apply that delivery must pass its result to `onApplicationFailed`, or the
  retry is skipped as a duplicate. The result names that one attempt, so a late
  failure from an attempt that already expired or was evicted is ignored. Retention is measured from each identity's own
  delivery time, so out-of-order timestamps still expire on time.
- `ReplayGuard` forgets identities after `retention` or beyond
  `maxTrackedIdentities`. Replays after eviction are not detected;
  `evictedIdentities` shows how often that happened.
- `EventTimeMonitor` assumes comparable clocks and does not correct skew. It
  keeps a bounded number of keys, recent decisions per key and recent arrivals.
- `EventTimeMonitor` judges a redelivered event by its first ingestion and
  reports each event and decision pair once, but only while the event is among
  the `maxTrackedArrivals` most recent arrivals. A redelivery after that can
  report a late arrival that its first delivery did not have.
- Timestamps that fall after the decision, from the read or the oracle, are
  reported as suspected `conflicting-values` findings rather than thrown.
- State is in memory and per process. Durable or distributed tracking is not
  implemented.
- A sink or check that throws, such as an oracle whose database is down, is
  isolated from the host path and counted in `sinkFailures` or `checkFailures`.
  The detector then reports nothing for that call, so a failure can hide a
  finding.
