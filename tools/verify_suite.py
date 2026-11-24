"""Verify all synthetic cases in a suite ZIP offline using Python only.

Version-3 suites hold 19 cases across six scenarios. Version-2 and version-1 suites,
which hold the first four scenarios and 12 cases, remain verifiable.
"""

import argparse
import hashlib
import json
import tempfile
import zipfile
from pathlib import Path

from verify_conflicting_values_results import verify_conflicting_values_results
from verify_event_time_results import verify_event_time_results
from verify_contract_results import verify_contract_results
from verify_missing_context_results import verify_missing_context_results
from verify_preview import verify_preview
from verify_replay_results import verify_replay_results
from verify_results import exact_integer, load_json, verify


SCENARIOS = (
    ("stale-context", "stale-context.jsonl", verify),
    ("contract-unit-drift", "contract-unit-drift.jsonl", verify_contract_results),
    ("duplicate-replay", "duplicate-replay.jsonl", verify_replay_results),
    ("event-time-ordering", "event-time-ordering.jsonl", verify_event_time_results),
    ("missing-context", "missing-context.jsonl", verify_missing_context_results),
    ("conflicting-values", "conflicting-values.jsonl", verify_conflicting_values_results),
)

# Version-1 manifests (in version-2 suites) index the first four scenarios; version-2 manifests index all six.
MANIFEST_SCENARIOS = {1: SCENARIOS[:4], 2: SCENARIOS}


def verified_manifest(directory, manifest_version=2):
    directory = Path(directory)
    scenarios = []
    reference = None
    for scenario_id, name, verifier in MANIFEST_SCENARIOS[manifest_version]:
        path = directory / name
        try:
            records = verifier(path)
        except (ValueError, KeyError, TypeError, OSError) as error:
            raise ValueError(f"{scenario_id}: {error}") from error
        for row in records:
            metadata = row["reproduction"]
            if reference is None:
                reference = metadata
            if (metadata["sourceCommitSha"] != reference["sourceCommitSha"]
                    or metadata["configuration"].get("sourceDirty") != "false"):
                raise ValueError(f"{scenario_id}: evidence must share one clean source commit")
            if any(metadata[key] != reference[key] for key in ("runtime", "jdk", "operatingSystem")):
                raise ValueError(f"{scenario_id}: runtime metadata disagrees")
        failure_ids = sorted({row.get("evaluation", row)["failureClassId"] for row in records})
        scenarios.append({"id": scenario_id, "verified": True, "recordCount": len(records),
                          "failureClassIds": failure_ids, "resultPath": name,
                          "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    return {"schemaVersion": manifest_version, "sourceCommit": reference["sourceCommitSha"],
            "sourceDirty": False, "scenarios": scenarios}


def verified_suite_summary(preview_path, replay_path, event_time_path, missing_path=None, conflicting_path=None):
    if (missing_path is None) != (conflicting_path is None):
        raise ValueError("Missing-context and conflicting-values evidence must be verified together")
    preview = verify_preview(preview_path)
    replay = verify_replay_results(replay_path)
    events = verify_event_time_results(event_time_path)
    missing = verify_missing_context_results(missing_path) if missing_path is not None else []
    conflicting = verify_conflicting_values_results(conflicting_path) if conflicting_path is not None else []
    for row in replay + events + missing + conflicting:
        metadata = row["reproduction"]
        if (metadata["sourceCommitSha"] != preview["sourceCommitSha"]
                or metadata["configuration"].get("sourceDirty") != "false"):
            raise ValueError("Suite evidence must share one clean source commit")
        runtime = {key: metadata[key] for key in ("runtime", "jdk", "operatingSystem")}
        if runtime != preview["runtime"]:
            raise ValueError("Suite evidence must share runtime metadata")

    def summaries(rows):
        return [{key: row[key] for key in ("caseId", "failureClassId", "status", "detected")} for row in rows]

    scenarios = {"stale-context": preview["staleContext"], "contract-unit-drift": preview["contractUnitDrift"],
                 "duplicate-replay": summaries(replay), "event-time-ordering": summaries(events)}
    if missing_path is not None:
        scenarios.update({"missing-context": summaries(missing), "conflicting-values": summaries(conflicting)})
    return {
        "suiteReportVersion": 1,
        "sourceCommitSha": preview["sourceCommitSha"],
        "runtime": preview["runtime"],
        "scenarios": scenarios,
    }


def verify_suite(path):
    required = {"suite-report.json", "README.txt", "preview.zip", "duplicate-replay.jsonl", "event-time-ordering.jsonl"}
    indexed = required | {"manifest.json", "stale-context.jsonl", "contract-unit-drift.jsonl"}
    complete = indexed | {"missing-context.jsonl", "conflicting-values.jsonl"}
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) not in (
                required, required | {"build.log"}, indexed, indexed | {"build.log"},
                complete, complete | {"build.log"}):
            raise ValueError("Suite must contain unique, expected file names only")
        if any(info.file_size > 16 * 1024 * 1024 for info in archive.infolist()):
            raise ValueError("Suite member exceeds the 16 MiB verification limit")
        contents = {name: archive.read(name) for name in names}
    report = load_json(contents["suite-report.json"])
    version = 3 if "missing-context.jsonl" in contents else 2 if "manifest.json" in contents else 1
    if not exact_integer(report["suiteReportVersion"], version):
        raise ValueError("Unsupported suite report version")
    evidence = set(names) - {"suite-report.json", "README.txt"}
    if set(report["files"]) != evidence:
        raise ValueError("Suite report must cover every evidence file")
    for name in evidence:
        entry = report["files"][name]
        if (not exact_integer(entry["bytes"], len(contents[name]))
                or entry["sha256"] != hashlib.sha256(contents[name]).hexdigest()):
            raise ValueError(f"Checksum or size mismatch: {name}")
    with tempfile.TemporaryDirectory(prefix="intenttrace-suite-verify-") as directory:
        root = Path(directory)
        for name in set(names) - {"suite-report.json", "README.txt", "build.log"}:
            (root / name).write_bytes(contents[name])
        complete_paths = ((root / "missing-context.jsonl", root / "conflicting-values.jsonl") if version == 3
                          else (None, None))
        expected = verified_suite_summary(root / "preview.zip", root / "duplicate-replay.jsonl",
                                          root / "event-time-ordering.jsonl", *complete_paths)
        if version >= 2:
            manifest_version = version - 1
            manifest = load_json(contents["manifest.json"])
            if not exact_integer(manifest["schemaVersion"], manifest_version):
                raise ValueError("Unsupported suite manifest version")
            computed = verified_manifest(root, manifest_version)
            if json.dumps(manifest, sort_keys=True) != json.dumps(computed, sort_keys=True):
                raise ValueError("Suite manifest disagrees with independently verified evidence")
            with zipfile.ZipFile(root / "preview.zip") as preview:
                for name in ("stale-context.jsonl", "contract-unit-drift.jsonl"):
                    if preview.read(name) != contents[name]:
                        raise ValueError(f"Nested preview disagrees with raw evidence: {name}")
            expected["suiteReportVersion"] = version
    actual = {key: value for key, value in report.items() if key != "files"}
    if json.dumps(actual, sort_keys=True) != json.dumps(expected, sort_keys=True):
        raise ValueError("Suite report disagrees with independently verified evidence")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("suite", type=Path)
    args = parser.parse_args()
    try:
        report = verify_suite(args.suite)
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile, RuntimeError) as error:
        parser.exit(1, f"Invalid suite: {error}\n")
    cases = sum(len(rows) for rows in report["scenarios"].values())
    print(f"Verified {cases} synthetic cases across {len(report['scenarios'])} scenarios "
          f"for source {report['sourceCommitSha']}.")
    print("Detection remains unknown; checksums do not authenticate the producer.")


if __name__ == "__main__":
    main()
