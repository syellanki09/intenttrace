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

The verifier reads JSONL independently of the Kotlin serializer and checks this scenario's outcomes, context/version evidence, paired metadata, and absent detection/degradation signals. For versioned configuration it also validates UTC timestamps and requires older commit <= latest commit <= decision boundary, retaining nanosecond precision. Agreement between metadata and evidence alone cannot make future or reversed history valid. It is a check for this demonstration, not a general provenance-schema validator or proof that all benchmark metadata is authentic.

## Scenario Configuration

The default inputs remain unchanged. To supply a strict JSON configuration:

```bash
./gradlew :benchmarks:run --args="--config benchmarks/config/stale-context.json"
```

The checked-in example lists all fields. Omitted fields take the documented example defaults; unknown keys fail. Configuration version 1 accepts a context ID, older/latest versions, commit and decision timestamps, and two categories. Versions must be positive and increasing, commit times ordered and no later than the decision, and categories nonblank and distinct. Equal commit timestamps are allowed: version order breaks ties.

Both paths share these inputs; only the resolver differs. The injected path pins the configured older version. Every effective input, including defaults, is recorded in `reproduction.configuration`, so the independent verifier and bundle tool can check customized outcomes. Existing output without a configuration version is interpreted as the original fixed scenario. Configuration files cannot set runtime/source metadata. Paths are resolved from the checkout when using Gradle; choose an output path different from the config file.

## Repetition and evidence

Rerun the same Gradle command to replace the output. Synthetic inputs, decision IDs, and timestamps are fixed; no seed or clock delay is involved. On the same source/runtime, output should be identical. Across environments, JDK/OS/runtime and source metadata may differ while logical outcomes remain unchanged. Archive the raw JSONL together with the full source commit and build log when sharing a reproduction; do not substitute rounded summaries for raw evidence.

For a source archive without Git, pass the known full source commit explicitly:

```bash
./gradlew :benchmarks:run --args="--source-commit FULL_SOURCE_COMMIT_SHA"
```

Replace the placeholder with the actual commit. Caller-supplied source identity is marked as such and is not verified by the runner. Event/ingestion timestamps and detection observations remain null because these are not modeled by this scenario.

## Result Bundles

From a clean checkout, generate and verify results, then package the raw evidence:

```bash
./gradlew :benchmarks:run --no-daemon
python3 tools/bundle_results.py benchmarks/build/results/stale-context/results.jsonl --output benchmarks/build/results/stale-context-bundle.zip
```

The ZIP contains unchanged `results.jsonl`, a `manifest.json` with source/runtime metadata and the raw file's SHA-256 and byte count, and reproduction commands in `README.txt`. Packaging rejects dirty or caller-supplied source metadata and invalid scenario evidence. For identical input bytes, the archive is deterministic. This checks the recorded clean-source marker; it does not authenticate the producer or prove the metadata was not edited.

Versioned-config results also include a checksummed `scenario-config.json` containing all effective scenario inputs. Use that file with `--config` to replay customized evidence; source/runtime metadata is excluded from the config. The archive README includes the command with an extraction-path placeholder.

CI uploads the bundle under `stale-context-<commit>` after tests and independent verification pass. Download it from the workflow run's artifacts section; retention is 30 days. For pull-request runs, the commit may be GitHub's tested merge commit. Preserve that identity when reproducing, rather than substituting a branch head. These are CI evidence bundles, not tagged reference releases or permanent archival deposits.

Verify a downloaded ZIP before extraction using Python alone:

```bash
python3 tools/verify_bundle.py /path/to/stale-context-bundle.zip
```

The verifier reads members without extracting them, rejects duplicate or unexpected file names, checks evidence sizes and SHA-256 hashes, validates the paired scenario results, and checks manifest/source metadata and replay configuration against the raw evidence. It exits nonzero for inconsistent bundles. CI runs this check before uploading artifacts. README text is informational and is not covered by the evidence checksums.

Both JSONL and bundle verification reject duplicate JSON object keys and nonstandard numeric constants (`NaN`/`Infinity`). Schema/context versions and evidence byte counts must be JSON integers: booleans and floating-point values are not accepted as equivalent integers. This avoids Python coercions and last-key-wins parsing hiding malformed evidence.

Checksums detect changes but do not provide signatures or authenticity guarantees: a consistently rewritten bundle can still pass. Obtain the expected source revision and artifact from a trusted workflow run. The tool is for these small version-1 scenario bundles, not a general-purpose ZIP scanner. To inspect extracted raw results separately, run `tools/verify_results.py` against the JSONL.

## Troubleshooting

Dependency download failures mean the scenario has not run: check network access to the hosts listed above. A failing verifier exits nonzero; preserve the output and report the commit, runtime, command, and error rather than editing the JSONL. Passing demonstrates this controlled stale-context failure, not its prevalence in production systems.
