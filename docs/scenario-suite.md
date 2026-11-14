# Run the Complete Scenario Suite

From a clean Git checkout with JDK 17 and Python 3.9+:

```bash
python3 tools/run_suite.py
```

The first build needs network access for Gradle and dependencies. The command
runs the full Kotlin and Python suites, executes all four demonstrations, checks
their evidence independently, and prints the location of a new
`build/suite/COMMIT-RUN/suite.zip` and `suite-report.json`. Each run gets a unique
directory. The working tree and commit must remain unchanged during execution.

## Expected Outcomes

| Scenario | Cases in order | Expected statuses |
| --- | --- | --- |
| Stale context | Control, pinned older version | SATISFIED, VIOLATED |
| Duration contract | Coordinated upgrade, unit drift, same-version drift | SATISFIED, VIOLATED, VIOLATED |
| Duplicate replay | Control, naive replay, deduplicated replay | SATISFIED, VIOLATED, SATISFIED |
| Event time | Control, reordered arrival, reconciliation, late arrival | SATISFIED, VIOLATED, SATISFIED, VIOLATED |

All 12 `detected` observations remain null. The harness evaluates synthetic ground
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

- `suite-report.json`: suite schema version 1, exact source SHA, runtime, and all
  per-case summaries, plus SHA-256 hashes and byte counts for evidence files.
- `preview.zip`: the existing five-case preview format, including raw context and
  contract JSONL and the independently verified context bundle.
- `duplicate-replay.jsonl`: three replay cases and delivery/application evidence.
- `event-time-ordering.jsonl`: four ordering/late-arrival cases with event,
  ingestion, and decision timestamps.
- `build.log`: output of the build, scenario tasks, and Python tests when generated
  by the suite runner.
- `README.txt`: source identity, reproduction command, and limitations.

The verifier rejects missing or unexpected members, duplicate filenames, invalid
checksums, false summaries, inconsistent scenario evidence, mixed commits,
dirty-source evidence, and mixed runtime metadata. It reads known filenames
without extracting archive paths and limits each member to 16 MiB. The nested
preview verifier checks its own format and evidence.

Checksums establish consistency, not producer authenticity. Offline checking does
not execute the source or validate build-log claims. Re-running the documented
command from the recorded commit provides a fresh reproduction. Logs and runtime
metadata can vary; the synthetic outcomes should agree.

## Failures and Scope

A failed build or verification exits nonzero, retains `build.log`, and does not
leave a successful suite ZIP or suite report for that attempt. Report the full
commit, command, environment, and relevant diagnostics using the repository's
reproduction issue form. CI's cold-cache reproduction runs this command and
retains the resulting suite evidence for 30 days.

The suite uses existing fixed demonstrations. It does not add production
integrations, durable processing guarantees, missing/conflicting-information
benchmarks, or detection claims. See the individual scenario guides for their
assumptions. The five-case `run_preview.py` and `verify_preview.py` commands and
published preview artifacts retain their formats; the complete suite is a
separate bundle with its own versioned report.
