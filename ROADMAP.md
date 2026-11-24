# IntentTrace Roadmap

This roadmap describes the technical progression of the reference implementation. Dates are intentionally omitted; releases should be driven by reproducibility and API stability rather than calendar pressure.

## Milestone 0 - Foundation

- Repository hygiene and development conventions
- Reference architecture
- Benchmark methodology
- Deterministic scenario contract
- CI/reproducibility baseline

## Milestone 1 - Stale-context reference scenario

- Synthetic versioned context model
- Minimal reference decision pipeline
- Deterministic stale-context injection
- Invariant evaluation
- Structured decision provenance
- Machine-readable benchmark result

**Release goal:** first reproducible research preview.

## Milestone 2 - Reproduction and release hardening

- Clean-checkout quick start
- Stable scenario configuration
- Automated tests and CI
- Reference result bundle
- Release/citation metadata
- Independent reproduction instructions

## Milestone 3 - SDK and data-contract integrity

- Versioned producer/consumer fixtures
- Compatibility failure scenarios
- Cross-client version/context drift examples
- Provenance that identifies version and interpretation mismatches

## Milestone 4 - Event-time and replay integrity

Implemented: a [fixed duplicate-replay demonstration](docs/duplicate-replay.md)
with naive/idempotent comparison, delivery evidence, and independent verification.
An [event-time ordering demonstration](docs/event-time-ordering.md) now compares
arrival-order processing, event-time reconciliation, and an event arriving after
the decision boundary, with three-clock evidence and independent verification.
Durable replay handling, watermarks, and repair of published decisions remain planned.

- Event-time vs processing-time scenarios
- Delayed/out-of-order events
- Duplicate/replayed information
- Idempotency and reconciliation comparisons

## Milestone 5 - External reproduction and extensibility

Implemented: a [complete scenario suite](docs/scenario-suite.md) runs all four
demonstrations and packages 12 independently verified cases in one command,
with a combined report and Python-only offline verification.
Its [consolidated reference results](docs/reference-results.md) summarize the
implemented experiments. An explicit raw-evidence manifest indexes record counts,
hashes, and verification status; ordinary and cold-cache CI verify suite archives.

- Contributor-friendly scenario interface
- Structured reproduction issue/report template
- Additional scenarios proposed or reproduced by external users
- Visualization/reporting improvements driven by real usability needs

## Milestone 6 - Runtime detection

In progress: an experimental [runtime detector](docs/detector.md) and
OpenTelemetry publishing, with agreement against fixture ground truth.

- Detector checks usable inside host systems without the harness
- OpenTelemetry metrics and span events for findings
- Measured precision, recall and overhead on realistic infrastructure (a
  [synthetic Kafka and Redis benchmark](docs/system-benchmark.md) exists; real
  workloads remain open)
- A decision-recording call separate from the oracle check, so freshness checks
  can run off the decision path without losing late-arrival detection
- Contract-compatibility runtime checks
- Durable or distributed replay and event-time tracking

## Release policy

- Prefer small, reproducible releases over feature-count milestones.
- Do not call a release `1.0` until the scenario/result interfaces are stable enough for outside users to depend on them.
- Archive meaningful tagged releases with citation metadata.
- Document breaking changes explicitly.
