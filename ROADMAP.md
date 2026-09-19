# IntentTrace Roadmap

This roadmap describes the technical progression of the reference implementation. Dates are intentionally omitted; releases should be driven by reproducibility and API stability rather than calendar pressure.

## Milestone 0 - Foundation

- Repository hygiene and development conventions
- Reference architecture
- Benchmark methodology
- Deterministic scenario contract
- CI/reproducibility baseline

## Milestone 1 - Stale-context reference scenario

- Synthetic versioned context model
- Minimal reference decision pipeline
- Deterministic stale-context injection
- Invariant evaluation
- Structured decision provenance
- Machine-readable benchmark result

**Release goal:** first reproducible research preview.

## Milestone 2 - Reproduction and release hardening

- Clean-checkout quick start
- Stable scenario configuration
- Automated tests and CI
- Reference result bundle
- Release/citation metadata
- Independent reproduction instructions

## Milestone 3 - SDK and data-contract integrity

- Versioned producer/consumer fixtures
- Compatibility failure scenarios
- Cross-client version/context drift examples
- Provenance that identifies version and interpretation mismatches

## Milestone 4 - Event-time and replay integrity

- Event-time vs processing-time scenarios
- Delayed/out-of-order events
- Duplicate/replayed information
- Idempotency and reconciliation comparisons

## Milestone 5 - External reproduction and extensibility

- Contributor-friendly scenario interface
- Structured reproduction issue/report template
- Additional scenarios proposed or reproduced by external users
- Visualization/reporting improvements driven by real usability needs

## Release policy

- Prefer small, reproducible releases over feature-count milestones.
- Do not call a release `1.0` until the scenario/result interfaces are stable enough for outside users to depend on them.
- Archive meaningful tagged releases with citation metadata.
- Document breaking changes explicitly.