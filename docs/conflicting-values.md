# Conflicting Values Demonstration

This fixed synthetic scenario tests whether a decision that consults two sources
for the same fact resolves a disagreement by a defined rule and records that it
did so, rather than silently taking whichever answer arrived last. It adds a
runnable `conflicting-values` example.

## Run and Verify

From the repository root with JDK 17 and Python 3.9+:

```bash
./gradlew :benchmarks:runConflictingValuesScenario --no-daemon
python3 tools/verify_conflicting_values_results.py benchmarks/build/results/conflicting-values/results.jsonl
```

The Gradle task also accepts `--args='--output /path/to/results.jsonl'` and
`--args='--help'`. A source archive without Git can use `--source-commit FULL_SHA`;
the evidence marks that identity as caller-supplied. Git checkouts record their
actual source SHA and whether the working tree is dirty. No seed or configurable
workload is needed for this fixed scenario.

## Controlled Input

A credit decision at `2026-01-01T00:08:00Z` reads the limit for `account-17`
from a primary store and a cache. The primary always answers first, with version
7, value `500`, committed at `00:04:00`. Injection writes a divergent value to
the cache in two ways: once as version 8 at `00:06:00`, a write the primary never
kept, and once under the primary's own version 7.

| Case | Cache answer | Host policy | Used | Resolution record | Evaluation |
| --- | --- | --- | --- | --- | --- |
| `control` | version 7, `500` | Last arrival | cache, `500` | none needed | SATISFIED |
| `last-arrival-wins` | version 8, `750` | Last arrival | cache, `750` | none | VIOLATED |
| `recorded-resolution` | version 8, `750` | Prefer primary and record | primary, `500` | `prefer-primary`, selected `primary` | SATISFIED |
| `same-version-divergence` | version 7, `750` | Last arrival | cache, `750` | none | VIOLATED |

Agreeing sources need no record. When they disagree, only a decision that
follows a recorded rule satisfies the invariant. `detected` is null in all four
records: the harness evaluates ground truth and makes no detection claim.

## Evidence and Invariant

Each line uses conflicting-values schema version 1 and contains source/runtime
metadata, case and policy, the fact key and decision time, every source
observation in arrival order (source, version, commit time, value), the source
and value the decision used, the resolution record or null, the canonical
`conflicting-values` failure class, the evaluation status, and the separate
detection observation.

`ConflictingValuesInvariant` treats any difference in value as a conflict,
whatever the versions say. A conflict is satisfied only when a resolution record
names a rule and the decision used the source that rule selected; a recorded
rule the decision did not follow still violates it. It rejects a single source,
repeated sources, and a used or selected source that no observation reported.
The Python verifier recomputes the observations, the used value, the resolution
record and the evaluation from the fixed input, rather than trusting the
recorded status.

CI runs the scenario, verifies its JSONL, and retains `conflicting-values-SHA`
artifacts for 30 days. The [scenario suite](scenario-suite.md) includes it.

## Detector Agreement

The [agreement run](detector.md#agreement-with-ground-truth) gives
`FreshnessCheck` the primary store as its oracle and reports the version the
decision used. It reports nothing for `control` and `recorded-resolution`, and a
suspected `conflicting-values` finding for `last-arrival-wins`, where the
decision used version 8 although the primary's latest is 7. It misses
`same-version-divergence`: the read and the oracle agree on version 7, and the
check compares versions, not values. The run records that case as a false
negative. `ReplayGuard` reports conflicting payloads under one event identity,
but it is not driven here, because the agreeing control would read as a
duplicate delivery.

## Scope

The example demonstrates two sources, one fact and one decision. It does not
model more than two sources, quorum reads, vector clocks, conflict-free
replicated data types, or resolution rules other than preferring one source. It
is not a claim about how often silent conflicts occur in production.
