# Contributing to IntentTrace

IntentTrace is an experimental clean-room research framework. Reproduction attempts and concrete usability feedback are useful even when you do not plan to change code.

## Try the Existing Scenarios

Start with the [README](README.md), [stale-context reproduction guide](docs/reproduction.md), and [contract demonstration](docs/contract-compatibility.md). Use a clean Git checkout, JDK 17, and Python 3.9+ for independent output checks. First builds need network access for dependencies.

```bash
git rev-parse HEAD
git status --short
./gradlew clean test :benchmarks:run --no-daemon
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/verify_results.py benchmarks/build/results/stale-context/results.jsonl
./gradlew :benchmarks:runContractScenario --no-daemon
```

Record the source commit, commands, runtime, and observed results. The stale-context pair should evaluate SATISFIED then VIOLATED; the contract demonstration should show a compatible upgrade and two unit-drift violations. Detection observations remain unknown, not successful detection. Consult the documentation at the exact revision you tested for the supported output formats.

## Supported Scope

| Area | Current status |
| --- | --- |
| Stale-context decisions | Executable paired scenario, provenance, JSONL, verification, and evidence bundles |
| Contract compatibility | Synthetic duration-unit comparison and producer/consumer demonstration; see revision-specific contract docs for output support |
| Missing/delayed/duplicate/conflicting information | Taxonomy and selected decision semantics; no complete executable benchmark for these classes |
| System detection | No independent detector is implemented; null observations remain unknown |
| Releases and archival identifiers | Preview preparation; use actual published repository metadata rather than assuming a tagged release or DOI |

## Give Feedback

Use **Reproduction failure** in the repository's New issue menu when documented commands fail, results differ, or verification rejects an artifact. Include the full commit, expected/actual behavior, exact command, environment, and relevant output. A dependency-download failure is different from a scenario failure; report which stage failed.

Use **Usage feedback or suggestion** for confusing documentation, missing diagnostics, integration needs, and proposed scenarios. Describe what you tried to accomplish, what happened, and what a useful result would look like. Successful reproductions are welcome too: include the commit and environment rather than claiming broad compatibility.

Use synthetic inputs and remove credentials or private data from attached logs. Do not assume a passing scenario establishes production prevalence, business impact, or general compatibility.

## Make a Change

1. Start from current main and create a descriptive feature or fix branch. Keep independent changes separate.
2. Follow the existing Kotlin module conventions and keep new dependencies justified. Use a maintained parser or serializer for structured data.
3. For a scenario, identify the canonical failure class, controlled input, reference/control path, injection, expected invariant, and evidence. Keep harness ground truth separate from observed system detection.
4. Add focused regression coverage for behavior changes. Run the relevant module tests, the full suite when shared behavior changes, Python tests if tools change, and `git diff --check`.
5. Open a PR explaining the problem, changed behavior, validation, and limitations. Include actual command results; state checks you could not run. Wait for review and CI before merging.

Do not add a generic invariant interface solely to unify different input types. Context freshness and cross-client contract consistency use different evidence. Avoid widening existing formats without an explicit schema-version and compatibility decision.

## Scope and Releases

Implemented behavior is documented in the current source and [changelog](CHANGELOG.md); future scope is in the [roadmap](ROADMAP.md). Production infrastructure integrations, other failure classes, and general compatibility proofs are not implied by the existing demonstrations.

Release preparation follows the [preview checklist](docs/release-checklist.md). Opening a PR does not authorize publishing a release, changing citation identifiers, or moving a tag.
