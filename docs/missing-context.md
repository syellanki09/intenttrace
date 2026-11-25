# Missing Context Demonstration

This fixed synthetic scenario tests whether a decision that loses its required
context fails visibly or degrades in a recorded, attributable way, rather than
silently using a default. It adds a runnable `missing-context` example.

## Run and Verify

From the repository root with JDK 17 and Python 3.9+:

```bash
./gradlew :benchmarks:runMissingContextScenario --no-daemon
python3 tools/verify_missing_context_results.py benchmarks/build/results/missing-context/results.jsonl
```

The Gradle task also accepts `--args='--output /path/to/results.jsonl'` and
`--args='--help'`. A source archive without Git can use `--source-commit FULL_SHA`;
the evidence marks that identity as caller-supplied. Git checkouts record their
actual source SHA and whether the working tree is dirty. No seed or configurable
workload is needed for this fixed scenario.

## Controlled Input

A pricing decision at `2026-01-01T00:08:00Z` needs the discount tier for
`member-42`. The harness commits version 4, value `gold`, at `00:05:00`, so the
tier is available before the decision. Injection makes the tier source answer
empty while reporting healthy, so the decision path receives nothing. The
host's fallback is the default tier `standard`.

| Case | Source answer | Host policy | Used value | Degradation record | Evaluation |
| --- | --- | --- | --- | --- | --- |
| `control` | version 4, `gold` | Default silently | `gold` | none | SATISFIED |
| `silent-default` | empty | Default silently | `standard` | none | VIOLATED |
| `recorded-degradation` | empty | Default and record | `standard` | `context-source-empty` from `tier-service` | SATISFIED |

Both injected cases make the same decision. The difference is whether the host
leaves evidence that the decision ran without its context and which source
failed. `detected` is null in all three records: the harness evaluates ground
truth and makes no detection claim.

## Evidence and Invariant

Each line uses missing-context schema version 1 and contains source/runtime
metadata, case and policy, the context key and decision time, the required
snapshot (version, commit time, value), the delivered snapshot or null, the
default and used values, the degradation record or null, the canonical
`missing-context` failure class, the evaluation status, and the separate
detection observation.

`MissingContextInvariant` is satisfied when the decision used the delivered
value, or when nothing was delivered and a degradation record names a reason
and a source. A silent default violates it. It rejects a delivered snapshot that
differs from the required one, since that is stale-context evidence, and a
degradation record alongside a successful read. The Python verifier recomputes
the delivered snapshot, used value, degradation record and evaluation from the
fixed input and the serialized evidence, rather than trusting the recorded status.

CI runs the scenario, verifies its JSONL, and retains `missing-context-SHA`
artifacts for 30 days. The [scenario suite](scenario-suite.md) includes it.

## Detector Agreement

The [agreement run](detector.md#agreement-with-ground-truth) gives
`FreshnessCheck` the tier store's latest committed version as its oracle and
reports what the decision read. It reports nothing for `control` and a confirmed
`missing-context` finding for `silent-default`. For `recorded-degradation` it
also reports `missing-context`, because it cannot see the host's degradation
record; the run scores that as `detected-and-prevented`, the label it uses when a
host already handled the fault the detector reports.

## Scope

The example demonstrates one empty read and one fallback policy. It does not
model partial payloads, timeouts, retries, cascading defaults across services,
or an enum mapped to a default downstream of a contract change. It is not a
claim about how often silent defaults occur in production.
