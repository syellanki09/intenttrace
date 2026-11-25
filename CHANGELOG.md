# Changelog

## Unreleased

- Executable missing-context and conflicting-values demonstrations, so every
  taxonomy class has at least one fixed synthetic scenario. Missing context
  compares a delivered value, a silent default after an empty read, and a
  recorded degradation; conflicting values compares agreeing sources, silent
  last-arrival picks of a newer or same-version divergent value, and a recorded
  prefer-primary rule. Each has its own invariant, provenance schema, Python
  verifier, CI job and guide. The suite now runs six families and 19 cases,
  with suite report 3 and manifest schema 2; earlier suites remain verifiable.
  The detector-agreement run covers 16 cases and records one known false
  negative, a divergent value under the same version.

- Kafka and Redis backed synthetic system benchmark (`system-benchmark` module,
  Docker Compose with pinned images). It runs the detector off, with inline
  decision checks, and with off-path checks against the same seeded workload
  and injected faults; measures decision latency, saturation throughput, CPU and
  allocation per operation, oracle query time and retained heap; and scores
  findings against ground truth that an independent evaluator derives from the
  host's logs. CI runs a short smoke repetition.

- Maven Central publication for `intenttrace-detector` and
  `intenttrace-detector-otel` under `io.github.syellanki09`, with sources, API
  documentation, complete POM metadata, reproducible archives and optional
  signing. A bundle checker enforces Central Portal rules, an outside consumer
  example builds only from the published artifacts, and a manual release
  workflow validates the tag commit before signing or uploading anything.
  Release metadata tooling sets version, date and citation together and checks
  that they agree.

- Experimental runtime detector (`detector` module, no dependencies) with
  oracle-backed and age-based freshness checks, missing-context reporting,
  bounded duplicate and conflicting-delivery detection, and event-time state
  regression and late-arrival detection. A `detector-otel` module publishes
  findings as a bounded-cardinality OpenTelemetry counter and span events, and
  a JSONL sink writes deterministic records. A detector-agreement runner
  compares findings with harness ground truth on 16 covered fixture cases;
  existing scenario schemas and `detected = null` values are unchanged.

- Verified release-attachment preparation from an explicitly selected source,
  including a private checked snapshot, exact ZIP/manifest/report bytes, and
  `SHA256SUMS`. The suite's optional `--release-assets` flag exports these files;
  ordinary and cold-cache CI check their hashes and retain review attachments.

- One-command execution of all four demonstrations and 12 cases, with a versioned
  combined suite report, independently verified evidence bundle, offline suite
  verification, and cold-cache CI reproduction.
- A version-1 raw-evidence manifest with independently checked record counts,
  hashes, canonical scenario/failure IDs, paths, and verification flags;
  deterministic suite packaging; live scenario diagnostics; and consolidated
  reference results. Normal CI packages and verifies its existing granular outputs.
  Indexed suite reports use version 2; version-1 suites remain readable.

- Executable event-time ordering, reconciliation, and late-arrival comparisons
  with independent evaluation, occurrence/ingestion/decision timestamps,
  canonical stale/delayed classifications, JSONL verification, and CI evidence.

- Executable duplicate-replay control, naive replay, and idempotent replay
  comparisons with independent evaluation, versioned event evidence, a Python
  verifier, and retained CI results.

- Offline verification of the complete preview pack, including independent
  scenario checks, report consistency, and bounded archive reads.

## 0.1.0-preview.1 - 2026-11-09

### Added

- Immutable versioned context snapshots and decision-boundary context resolution.
- Deterministic stale-context injection with paired control and injected execution.
- Independent freshness evaluation with satisfied, violated, and not-applicable statuses.
- Separate nullable system-detection observations and canonical failure-class identifiers.
- Structured decision provenance and versioned JSON records.
- One-command scenario execution, validated JSON configuration, and JSONL output.
- Independent result verification, reproducible evidence bundles, and offline bundle checks.
- Clean-checkout reproduction instructions and CI tests with retained evidence artifacts.
- Executable cross-client unit-drift experiments with contract-specific provenance, JSONL output, and independent wire-evidence verification.
- One-command preview verification and a combined evidence pack for all five synthetic cases.

### Preview Limitations

- Executable scenarios cover stale context and a narrow synthetic duration-contract unit mismatch; the other taxonomy entries describe planned scope.
- No system detection signal is implemented. A violated invariant does not imply successful detection.
- Event and ingestion stages are not modeled; their provenance fields remain unavailable.
- This is an experimental prerelease, not a stable API. No archival DOI is established.
- Unsigned bundles check evidence consistency, not authenticity. Runtime metadata can differ between reproductions.
