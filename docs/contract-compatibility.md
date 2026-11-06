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

## Executable Unit-Drift Demonstration

The benchmark module now includes actual synthetic producer/consumer adapters, separate from the recorded fixtures above:

```bash
./gradlew :benchmarks:runContractScenario --no-daemon
```

`DurationProducer` encodes a logical duration into JSON, and `DurationConsumer` parses that JSON and applies its assumed unit. Both versions have the same field shape. The permissive consumer deliberately does not enforce the producer's contract version or unit; this models successful parsing with incorrect interpretation rather than an exception or detection signal.

For a 2,000-millisecond input, the reference web client produces `durationMillis:2000`. A coordinated seconds-based v2 producer/client pair also produces 2,000 milliseconds and satisfies consistency. A v2 producer that still sends milliseconds to the seconds-based client produces 2,000,000 milliseconds and violates consistency. A same-version pair with those mismatched assumptions also violates consistency. Detection remains null in all cases.

The experiment retains raw wire payload, producer unit, consumer assumption, logical input, and both evaluated client observations. Repeated execution is deterministic. Unsupported negative durations, lossy integer-second conversions, and arithmetic overflow fail visibly instead of generating misleading outcomes. Zero illustrates a limitation: both unit interpretations yield zero, so the outcome comparison does not claim a violation for that input.

## Machine-Readable Evidence

The command prints the three cases and writes `benchmarks/build/results/contract-unit-drift/results.jsonl`, replacing earlier output. The version-1 contract record includes reproduction metadata, case ID, logical duration, raw reference and observed wire payloads, producer/consumer unit assumptions, both client observations, the canonical failure-class ID, invariant status, and nullable detection. It is a separate format from context provenance; no context versions or event timestamps are fabricated.

Verify the generated file independently:

```bash
python3 tools/verify_contract_results.py benchmarks/build/results/contract-unit-drift/results.jsonl
```

The Python verifier parses the raw wire strings independently of Kotlin, recomputes integer-second interpretation, and checks reference/observed outcomes and the resulting status. It also checks fixed case order, units, contract versions, shared reproduction metadata, and absent detection signals. It is intentionally scoped to the fixed version-1, 2,000-millisecond experiment, not an arbitrary contract schema or authenticity proof.

```bash
./gradlew :benchmarks:runContractScenario --args="--output /tmp/contract-results.jsonl"
./gradlew :benchmarks:runContractScenario --args="--help"
```

Git source identity and working-tree dirtiness are captured automatically. Source archives can pass `--source-commit FULL_SHA`, which is explicitly marked caller-supplied. Repeated execution on the same source/runtime produces equivalent records; runtime and source metadata differ across environments. CI verifies and retains raw contract JSONL as a separate artifact for 30 days.

The stale-context JSONL/bundle tools remain scoped to stale context; contract evidence is not fed through those tools. Matching one reference input does not prove general schema or semantic compatibility, and missing observations are not treated as successful comparisons.
