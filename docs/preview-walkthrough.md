# Try and Review the Experimental Preview

This walkthrough prepares a review pack from a specific Git commit. It does not publish a software release or establish a DOI. You need Git, JDK 17, Python 3.9+, and network access to download Gradle and dependencies on the first run.

## One Command

From a clean checkout, select the versioned preview (or stay on main to test development changes):

```bash
git checkout v0.1.0-preview.1
python3 tools/run_preview.py
```

The script resolves the repository from its own location, so it does not depend on your current working directory. It runs the full Kotlin suite, both demonstrations, and the Python suite; independently verifies both result formats; checks that the evidence shares the same clean source commit and runtime; and verifies the stale-context bundle. It stops with a nonzero exit code if any stage fails.

On success it prints the location of a new `build/preview/<commit-prefix>-<run-id>/preview.zip` and `report.json`. Each run has its own directory, preserving earlier packs. A failed attempt retains `build.log` for diagnosis but does not create a successful report or pack. Commit or set aside tracked/untracked changes before starting; ignored build outputs do not dirty the checkout. Ordinary individual-demo commands still support experimentation with modified source.

## What You Should See

| Demonstration | Case | Expected evaluation |
| --- | --- | --- |
| Stale context | Latest eligible version used | SATISFIED |
| Stale context | Older pinned version used | VIOLATED |
| Contract compatibility | Coordinated version upgrade | SATISFIED |
| Contract compatibility | Milliseconds interpreted as seconds | VIOLATED |
| Contract compatibility | Same-version semantic drift | VIOLATED |

`detected` remains null in all five records. The harness knows which invariants were violated; no independent system detector is implemented. The unit-drift verifier recomputes the client outcomes from raw JSON wire values. The context verifier checks the used/latest evidence and decision boundary.

## Inspect the Pack

Verify a downloaded `preview.zip` offline using Python 3.9+ from a checkout
containing the verifier (no JDK, Gradle, or dependency downloads are needed):

```bash
python3 tools/verify_preview.py /path/to/preview.zip
```

This checks archive membership, evidence hashes and sizes, both demonstrations,
the nested bundle, shared clean-source/runtime metadata, and the report summaries.
It rejects inconsistent evidence even when its checksums have been recomputed.
It does not execute or authenticate the recorded source, validate build-log
claims, or establish independent human reproduction. Each outer archive member
is limited to 16 MiB during verification.

The archive contains:

- `report.json`: exact source SHA, runtime details, per-case statuses and detection observations, and evidence SHA-256/byte counts.
- `stale-context.jsonl`: two raw decision/provenance records.
- `contract-unit-drift.jsonl`: three raw producer/consumer evidence records.
- `stale-context-bundle.zip`: the existing independently verified context bundle with replay configuration.
- `build.log`: output from the build, demonstrations, and test commands.
- `README.txt`: source identity, reproduction command, and limitations.

The outer pack is not the stale-context ZIP format; use `tools/verify_bundle.py` on the nested stale-context bundle, not `preview.zip`. Checksums show file consistency, not producer authenticity. For another reproduction, check out the recorded full commit and rerun the command. Runtime metadata and logs can vary across systems; synthetic outcomes should agree.

## Supported Scope and Feedback

This pack covers stale-context selection and a fixed synthetic duration-contract mismatch. Separate commands demonstrate [duplicate replay](duplicate-replay.md) and [event-time ordering with late arrival](event-time-ordering.md). Missing/conflicting information remain future executable-scenario work. No general SDK compatibility proof, production integration, or prevalence claim is made.

Try the walkthrough, inspect a violated record, and report the full source SHA, environment, exact command, and whether the result was understandable. Use the repository's **Reproduction failure** or **Usage feedback or suggestion** issue form. Follow [CONTRIBUTING.md](../CONTRIBUTING.md) for detailed steps. Successful independent attempts are useful evidence too; report what you actually ran.

## Versioned Preview

The `v0.1.0-preview.1` prerelease brings together two executable demonstrations, separate versioned provenance formats, independent output verification, a single review command, and contributor feedback forms. See its [release notes](releases/v0.1.0-preview.1.md) and the [release checklist](release-checklist.md). APIs and result formats remain experimental. No archival identifier is asserted.
