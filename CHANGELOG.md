# Changelog

## Unreleased

### Added

- Immutable versioned context snapshots and decision-boundary context resolution.
- Deterministic stale-context injection with paired control and injected execution.
- Independent freshness evaluation with satisfied, violated, and not-applicable statuses.
- Separate nullable system-detection observations and canonical failure-class identifiers.
- Structured decision provenance and versioned JSON records.
- One-command scenario execution, validated JSON configuration, and JSONL output.
- Independent result verification, reproducible evidence bundles, and offline bundle checks.
- Clean-checkout reproduction instructions and CI tests with retained evidence artifacts.

### Preview Limitations

- Only the stale-context failure class has an executable scenario; the other taxonomy entries describe planned scope.
- No system detection signal is implemented. A violated invariant does not imply successful detection.
- Event and ingestion stages are not modeled; their provenance fields remain unavailable.
- The current build is `0.1.0-SNAPSHOT`. No release date, tagged reference result, or archival DOI is established by this changelog.
- Unsigned bundles check evidence consistency, not authenticity. Runtime metadata can differ between reproductions.
