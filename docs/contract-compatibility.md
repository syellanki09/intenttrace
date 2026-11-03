# Cross-Client Contract Evaluation

`ContractCompatibilityInvariant` begins the SDK/data-contract milestone by comparing two recorded client outcomes for the same controlled logical input. It retains input/client identifiers and producer-consumer contract versions through `ClientContractDecision` and `ContractCompatibilityResult`.

The harness supplies a known reference outcome. Equal outcomes evaluate `SATISFIED`; divergent outcomes evaluate `VIOLATED` under the canonical `contract-incompatibility` class. Different version numbers alone do not imply incompatibility, and equal version numbers do not guarantee matching semantics. Blank identifiers/outcomes, nonpositive versions, and unrelated input comparisons are rejected as invalid experiments.

System detection remains a separate nullable caller observation. The evaluator never derives detection from the comparison, including false-positive and false-negative observations.

## Versioned Fixtures

Three synthetic test fixtures describe one logical duration of 2,000 milliseconds:

| Fixture | Reference | Observed | Result |
| --- | --- | --- | --- |
| Compatible upgrade | v1: 2,000 ms | v2: 2,000 ms | SATISFIED |
| Unit interpretation drift | v1: 2,000 ms | v2: 2,000,000 ms | VIOLATED |
| Same-version semantic drift | v1: 2,000 ms | v1: 2,000,000 ms | VIOLATED |

These are recorded synthetic client outcomes, not implementations of a producer, SDK, or unit converter. They test the comparison boundary and diagnostics. They do not establish why a real client's output diverged.

```bash
./gradlew :evaluation:test --no-daemon
```

This is a library evaluator, not a second executable benchmark. Producer/consumer adapters, contract-specific provenance, a runner, and independent contract-result verification remain follow-ups. The current provenance recorder and JSONL/bundle verifier remain scoped to stale context. Matching one reference input does not prove general schema or semantic compatibility, and missing observations are not treated as successful comparisons.
