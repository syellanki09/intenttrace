# IntentTrace Benchmark Methodology

**Status:** methodology draft. Numerical results should not be treated as reference results until the corresponding scenario and release are tagged.

## Objective

The IntentTrace benchmark suite evaluates whether a distributed decision path can detect, explain, and reproduce information-integrity failures that may not appear in conventional availability or error-rate monitoring.

## Experiment structure

Each benchmark consists of paired executions:

1. **Control execution** - the correctness invariant is preserved.
2. **Injected execution** - one specific failure mechanism is introduced deterministically.

The comparison should isolate the effect of the injected condition rather than compare unrelated architectures or providers.

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

Initial benchmarks should emphasize:

### Invariant correctness
Did the final decision satisfy the scenario's stated invariant?

### Detection
Did the framework identify that the information used by the decision diverged from the expected information state?

### Diagnostic provenance
Did the recorded provenance contain enough version/timing/input information to explain where the divergence occurred?

### Reproducibility
Does the same scenario configuration and seed reproduce the expected control and injected outcomes from a clean checkout?

## Result reporting

Results should be published in machine-readable form (for example JSONL or CSV) together with a short human-readable summary. Reports should preserve raw measurements rather than only rounded values used in prose or charts.

A result record should distinguish:

- expected state;
- observed state;
- detection outcome;
- diagnostic evidence;
- scenario/configuration metadata.

## Repetition and uncertainty

When a scenario includes nondeterministic timing, concurrency, or sampling, the benchmark should use a documented number of repetitions and report an appropriate uncertainty summary. Fully deterministic scenarios should state why repeated runs are or are not informative.

## Reproduction standard

A published reference result should include:

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