# Reproducing the stale-context demonstration

This guide covers the version-1 synthetic scenario, not a tagged reference release.

## Requirements

- Git and JDK 17 (the Gradle wrapper is included).
- Network access to Gradle distributions, Maven Central, and the Gradle Plugin Portal on the first build.
- Python 3.9 or newer for the optional independent output check.

## Clean-checkout procedure

```bash
git clone https://github.com/syellanki09/intenttrace.git
cd intenttrace
git rev-parse HEAD
git status --short
./gradlew clean test :benchmarks:run --no-daemon
python3 tools/verify_results.py benchmarks/build/results/stale-context/results.jsonl
```

Record the full commit ID printed above. To reproduce a specific revision, check out that commit before building. A clean checkout should produce `sourceDirty: "false"` in reproduction configuration; local modifications are recorded as dirty. The output directory is ignored by Git.

The runner prints a summary and replaces `benchmarks/build/results/stale-context/results.jsonl` with exactly two records:

| Execution | Used context | Latest eligible | Outcome | Evaluation | Detection |
| --- | --- | --- | --- | --- | --- |
| CONTROL | v2 | v2 | technology | SATISFIED | null |
| INJECTED | v1 | v2 | travel | VIOLATED | null |

The verifier reads JSONL independently of the Kotlin serializer and checks this scenario's outcomes, context/version evidence, paired metadata, and absent detection/degradation signals. It is a check for this demonstration, not a general provenance-schema validator or proof that all benchmark metadata is authentic.

## Repetition and evidence

Rerun the same Gradle command to replace the output. Synthetic inputs, decision IDs, and timestamps are fixed; no seed or clock delay is involved. On the same source/runtime, output should be identical. Across environments, JDK/OS/runtime and source metadata may differ while logical outcomes remain unchanged. Archive the raw JSONL together with the full source commit and build log when sharing a reproduction; do not substitute rounded summaries for raw evidence.

For a source archive without Git, pass the known full source commit explicitly:

```bash
./gradlew :benchmarks:run --args="--source-commit FULL_SOURCE_COMMIT_SHA"
```

Replace the placeholder with the actual commit. Caller-supplied source identity is marked as such and is not verified by the runner. Event/ingestion timestamps and detection observations remain null because these are not modeled by this scenario.

## Troubleshooting

Dependency download failures mean the scenario has not run: check network access to the hosts listed above. A failing verifier exits nonzero; preserve the output and report the commit, runtime, command, and error rather than editing the JSONL. Passing demonstrates this controlled stale-context failure, not its prevalence in production systems.
