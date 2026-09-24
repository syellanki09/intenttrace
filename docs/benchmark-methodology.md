# IntentTrace Benchmark Methodology

**Status:** methodology draft. Numerical results become reference results only when the corresponding scenario and source release are tagged.

## Objective

The IntentTrace benchmark suite evaluates whether a distributed decision path can detect, explain, and reproduce information-integrity failures that may not appear in conventional availability or error-rate monitoring.

## Experiment structure

Each benchmark consists of paired executions:

1. **Control execution** - the correctness invariant is preserved.
2. **Injected execution** - one specific failure mechanism is introduced deterministically.

The comparison isolates the injected condition; it does not compare unrelated architectures or providers.

## Required scenario metadata

Every published benchmark result must record:

- scenario name and version;
- source commit SHA;
- deterministic seed where applicable;
- runtime/JDK and operating-system information;
- scenario configuration;
- context/event identifiers and versions;
- relevant event, commit, ingestion, and decision timestamps;
- expected invariant;
- observed outcome;
- whether the failure was detected;
- provenance fields required to explain the result; 
- canonical failure-class ID;
- latest eligible state at the decision boundary;
- state actually used by the decision.

## Core measurements

Initial benchmarks measure:

### Invariant correctness
Did the final decision satisfy the scenario's stated invariant?

### Detection
Did the framework identify that the information used by the decision diverged from the expected information state?

### Diagnostic provenance
Did the recorded provenance contain enough version/timing/input information to explain where the divergence occurred?

### Reproducibility
Does the same scenario configuration and seed reproduce the expected control and injected outcomes from a clean checkout?

## Result reporting

Tagged reference releases publish results in machine-readable form, such as JSONL or CSV, together with a short human-readable summary. Raw measurements are preserved alongside any rounded values used in prose or charts.

A result record distinguishes:

- expected state;
- observed state;
- detection outcome;
- diagnostic evidence;
- scenario/configuration metadata.

## Repetition and uncertainty

When a scenario includes nondeterministic timing, concurrency, or sampling, the benchmark uses a documented number of repetitions and reports an appropriate uncertainty summary. Fully deterministic scenarios document whether repeated execution adds useful information.

## Reproduction standard

A published reference result includes:

```text
exact source tag or commit
runtime requirements
one-command scenario execution
seed/configuration
raw output location
aggregation/report command, if any
known limitations
```

## Limitations

IntentTrace uses synthetic, clean-room reference systems. Results demonstrate that a failure mechanism can be reproduced, detected, and diagnosed under the documented conditions. They do not by themselves establish how frequently that mechanism occurs in proprietary production environments or quantify business impact outside the benchmark.