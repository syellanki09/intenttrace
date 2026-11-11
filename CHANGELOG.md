# Changelog

## Unreleased

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
