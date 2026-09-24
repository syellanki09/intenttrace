# IntentTrace

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

Planned structure as the reference implementation lands:

```
docs/                  failure taxonomy, architecture notes, benchmark methodology
generator/             synthetic context and event workload generation
pipeline/              clean-room reference personalization pipeline
injectors/             one fault injector per failure class
provenance/            decision provenance capture and reporting
benchmarks/            scenario definitions, harness, and reference results
```

## Status

Prototype under active development.

The repository currently defines the project scope, canonical failure taxonomy, reference architecture, scenario contract, and benchmark methodology. The
executable reference pipeline, deterministic fault injectors, provenance model, and benchmark harness are the next implementation milestones.

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
