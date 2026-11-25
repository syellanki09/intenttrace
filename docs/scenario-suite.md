# Run the Complete Scenario Suite

From a clean Git checkout with JDK 17 and Python 3.9+:

```bash
python3 tools/run_suite.py
```

The first build needs network access for Gradle and dependencies. The command
runs the full Kotlin and Python suites, executes all six demonstrations, checks
their evidence independently, and prints the location of a new
`build/suite/COMMIT-RUN/suite.zip` and `suite-report.json`. Each run gets a unique
directory. The working tree and commit must remain unchanged during execution.
To also prepare a review attachment directory in the same run:

```bash
python3 tools/run_suite.py --release-assets build/release-assets
```

The new directory contains the verified `suite.zip`, exact `manifest.json` and
`suite-report.json`, and `SHA256SUMS`, all bound to the same clean source commit.
Relative attachment paths resolve from the repository root. Existing output
directories are rejected before starting a build; select a new path for each run.
This prepares local review files without changing release metadata or publishing.
Output from every subprocess is streamed to the terminal and retained in
`build.log`. Each scenario is independently verified before the next begins.

## Expected Outcomes

| Scenario | Cases in order | Expected statuses |
| --- | --- | --- |
| Stale context | Control, pinned older version | SATISFIED, VIOLATED |
| Duration contract | Coordinated upgrade, unit drift, same-version drift | SATISFIED, VIOLATED, VIOLATED |
| Duplicate replay | Control, naive replay, deduplicated replay | SATISFIED, VIOLATED, SATISFIED |
| Event time | Control, reordered arrival, reconciliation, late arrival | SATISFIED, VIOLATED, SATISFIED, VIOLATED |
| Missing context | Control, silent default, recorded degradation | SATISFIED, VIOLATED, SATISFIED |
| Conflicting values | Control, last arrival wins, recorded resolution, same-version divergence | SATISFIED, VIOLATED, SATISFIED, VIOLATED |

All 19 `detected` observations remain null. The harness evaluates synthetic ground
truth; it does not implement a system detector. The report preserves individual
case statuses and failure classes where present instead of turning violations
into a detection rate.

## Share and Verify

Share `suite.zip` with another reader. From a checkout containing the suite tools,
they can verify it offline with Python 3.9+ alone:

```bash
python3 tools/verify_suite.py /path/to/suite.zip
```

The archive contains:

- `manifest.json`: manifest schema version 2, full source SHA, clean-source flag,
  deterministic scenario order, canonical failure IDs, verification status,
  raw JSONL paths, independently counted records, and SHA-256 hashes.
- `suite-report.json`: suite report version 3, exact source SHA, runtime, and all
  per-case summaries, plus SHA-256 hashes and byte counts for evidence files.
- `stale-context.jsonl` and `contract-unit-drift.jsonl`: the original five raw
  records, also preserved byte-for-byte inside the preview ZIP.
- `preview.zip`: the existing five-case preview format, including raw context and
  contract JSONL and the independently verified context bundle.
- `duplicate-replay.jsonl`: three replay cases and delivery/application evidence.
- `event-time-ordering.jsonl`: four ordering/late-arrival cases with event,
  ingestion, and decision timestamps.
- `missing-context.jsonl`: three missing-context cases with the required and
  delivered snapshots, the used value, and any degradation record.
- `conflicting-values.jsonl`: four conflicting-values cases with every source
  observation in arrival order, the used source, and any resolution record.
- `build.log`: output of the build, scenario tasks, and Python tests when generated
  by the suite runner.
- `README.txt`: source identity, reproduction command, and limitations.

The verifier rejects missing or unexpected members, duplicate filenames or
manifest scenarios, incorrect record counts, invalid
checksums, false summaries, inconsistent scenario evidence, mixed commits,
dirty-source evidence, and mixed runtime metadata. It reads known filenames
without extracting archive paths and limits each member to 16 MiB. The nested
preview verifier checks its own format and evidence. The suite verifier also
continues to read earlier four-scenario suites: version-2 reports with a
version-1 manifest, and version-1 reports without a manifest. Release
attachments require a version-3 suite.

Archive member order, timestamps, and permissions are stable. Identical evidence
and logs produce identical ZIP bytes. Runtime details and build logs can differ
between executions; compare raw evidence and manifests when assessing repeatability.

Checksums establish consistency, not producer authenticity. Offline checking does
not execute the source or validate build-log claims. Re-running the documented
command from the recorded commit provides a fresh reproduction. Logs and runtime
metadata can vary; the synthetic outcomes should agree.

## Failures and Scope

A failed build or verification exits nonzero, retains `build.log`, and does not
leave a successful suite ZIP or suite report for that attempt. Report the full
commit, command, environment, and relevant diagnostics using the repository's
reproduction issue form. CI's cold-cache reproduction runs this command and
retains the resulting suite evidence for 30 days. Ordinary CI retains a separate
`intenttrace-suite-SHA` artifact, using `--package-only` after its granular scenario
checks to avoid rerunning the experiments. That option still verifies all inputs,
record counts, source identity, and the completed archive, but does not execute
tests or scenarios itself.

Both CI paths now export release review attachments and independently check
`SHA256SUMS`. Ordinary CI retains `preview-release-assets-SHA`; cold-cache CI
includes the four files in its existing reproduction artifact. Retention is 30
days. See the [candidate checklist](releases/preview-2-review.md) for final-source
review and publication requirements.

The suite uses existing fixed demonstrations. It does not add production
integrations, durable processing guarantees, or detection claims; detector
agreement is reported separately by `:benchmarks:runDetectorAgreement`. See the individual scenario guides for their
assumptions. The five-case `run_preview.py` and `verify_preview.py` commands and
published preview artifacts retain their formats; the complete suite is a
separate bundle with its own versioned report.

See the [reference-results page](reference-results.md) for a consolidated technical
description of every implemented family.
