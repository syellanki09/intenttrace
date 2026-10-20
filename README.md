# IntentTrace

[![CI](https://github.com/syellanki09/intenttrace/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/syellanki09/intenttrace/actions/workflows/ci.yml)

An open, nonproprietary reliability and evaluation framework for detecting, reproducing, tracing, and benchmarking **hidden information-integrity failures** in personalization, recommendation, and increasingly AI-enabled distributed systems.

## The problem

Conventional availability monitoring tells you whether a service is online and responding. It does not tell you whether the *right information* survived the full path from a user's action to the final result.

A personalization system can report healthy uptime on every service while the information used by a decision violates one of the six integrity classes defined below. The user sees an irrelevant or inconsistent result. The engineering team sees a system that appears to be working. Failures of this kind are difficult to diagnose precisely because they leave no crash and no error to measure.

IntentTrace targets that class of failure at the system level rather than asking only whether an individual service, database, or model is available.

## Scope

IntentTrace addresses six failure classes, documented in [`docs/failure-taxonomy.md`](docs/failure-taxonomy.md):

| ID | Class | Short description |
| --- | --- | --- |
| `stale-context` | Stale context | Decision made on context that has been superseded |
| `missing-context` | Missing context | Expected context absent; system proceeds on defaults |
| `delayed-events` | Delayed events | Eligible context arrives after the decision that needed it |
| `duplicate-replay` | Duplicate / replayed information | The same logical event is applied more than once or older state is replayed |
| `conflicting-values` | Conflicting values | Two sources disagree and the system resolves the conflict silently |
| `contract-incompatibility` | Version / data-contract incompatibility | Producer and consumer disagree on data shape or meaning |

For each class, the framework aims to provide:

- **Failure injection** — reproduce the condition deterministically against a reference pipeline
- **Detection measurement** — did the system notice, and where did the information diverge from its expected state?
- **Diagnostic provenance** — which contextual inputs, versions, and transformations produced the final outcome, so an engineer can reconstruct why an apparently healthy system produced an incorrect result
- **Reproducible benchmarks** — comparable results across reference architectures

A second area of focus is the reliability of **software development kits and other shared client components**. Because a shared SDK or data contract can propagate versioning, compatibility, or context-handling problems across many client applications at once, IntentTrace includes repeatable compatibility and context-integrity checks intended to surface such problems before or during deployment, and to compare the same reliability signals across web and mobile clients.

## Design constraints

- **Synthetic data only.** All workloads, events, and reference architectures are generated or clean-room constructed. No proprietary system data, configuration, or employer-derived material is used anywhere in this repository.
- **Open technologies.** No dependency on any commercial personalization platform.
- **Reproducible by others.** Every benchmark scenario is intended to be runnable end to end by an independent reader from a clean checkout.

## Repository layout

Current and planned structure:

```
docs/                  failure taxonomy, architecture notes, benchmark methodology
generator/             synthetic context and event workload generation
pipeline/              clean-room reference personalization pipeline
injectors/             one fault injector per failure class
evaluation/            independent invariant evaluation and detection observations
provenance/            structured decision evidence and JSON serialization
benchmarks/            scenario definitions, harness, and reference results
```

## Status

Prototype under active development.

The repository currently includes the canonical failure taxonomy, reference architecture, benchmark methodology, a versioned context model, and an executable reference decision pipeline with deterministic tests.

The pipeline records both the context version used by a decision and the latest version eligible at the decision boundary, and it explicitly marks fallback decisions caused by missing context as degraded.

The first deterministic stale-context injector, `StaleContextResolver` in the `injectors` module, selects an older eligible context version without modifying stored history. Paired tests show the control decision using the latest eligible version and the injected decision using the pinned older version. `freshnessViolated` records benchmark ground truth, not whether the evaluated system detected the problem.

The `evaluation` module independently compares recorded context versions through `FreshnessInvariant`. Results distinguish `SATISFIED`, `VIOLATED`, and `NOT_APPLICABLE` and retain expected and observed versions. Detection observations are supplied separately; `detected = null` means no observation was supplied. Missing context is not classified as a satisfied freshness invariant.

The next implementation milestones are:

- Machine-readable benchmark results
- An end-to-end scenario runner

No external adoption or third-party use is claimed.

The `provenance` module records decision evidence, evaluation results, and reproduction metadata separately from the pipeline. `ProvenanceJson` encodes compact, versioned JSON records with canonical failure-class IDs and explicit null detection observations. This is a library API; a standalone scenario runner and result-file command remain planned.

## Related work

The clean-room reproduction artifact accompanying the AISQ 2026 paper *"Operational Reliability Patterns in AI Personalization Serving Infrastructure: Practitioner-Grounded Hypotheses and a Clean-Room Reproduction Study"* is maintained separately at
[`personalization-serving-reliability-benchmark`](https://github.com/syellanki09/personalization-serving-reliability-benchmark).

That artifact establishes that representative personalization-serving reliability failures can be reproduced and measured outside proprietary systems. IntentTrace is a separate and broader effort: it generalizes that work into a reusable framework for failure classification, injection, tracing, provenance, compatibility testing, and benchmarking.

## Contributing

Issues and discussion are welcome, particularly:

- additional failure modes observed in production personalization or recommendation systems
- reference architectures worth adding to the benchmark set
- independent attempts to reproduce published results, including ones that fail

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
