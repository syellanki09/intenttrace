"""Verify all twelve synthetic cases in a suite ZIP offline using Python only."""

import argparse
import hashlib
import json
import tempfile
import zipfile
from pathlib import Path

from verify_event_time_results import verify_event_time_results
from verify_preview import verify_preview
from verify_replay_results import verify_replay_results
from verify_results import exact_integer, load_json


def verified_suite_summary(preview_path, replay_path, event_time_path):
    preview = verify_preview(preview_path)
    replay = verify_replay_results(replay_path)
    events = verify_event_time_results(event_time_path)
    for row in replay + events:
        metadata = row["reproduction"]
        if (metadata["sourceCommitSha"] != preview["sourceCommitSha"]
                or metadata["configuration"].get("sourceDirty") != "false"):
            raise ValueError("Suite evidence must share one clean source commit")
        runtime = {key: metadata[key] for key in ("runtime", "jdk", "operatingSystem")}
        if runtime != preview["runtime"]:
            raise ValueError("Suite evidence must share runtime metadata")

    def summaries(rows):
        return [{key: row[key] for key in ("caseId", "failureClassId", "status", "detected")} for row in rows]

    return {
        "suiteReportVersion": 1,
        "sourceCommitSha": preview["sourceCommitSha"],
        "runtime": preview["runtime"],
        "scenarios": {"stale-context": preview["staleContext"],
                      "contract-unit-drift": preview["contractUnitDrift"],
                      "duplicate-replay": summaries(replay), "event-time-ordering": summaries(events)},
    }


def verify_suite(path):
    required = {"suite-report.json", "README.txt", "preview.zip", "duplicate-replay.jsonl", "event-time-ordering.jsonl"}
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) not in (required, required | {"build.log"}):
            raise ValueError("Suite must contain unique, expected file names only")
        if any(info.file_size > 16 * 1024 * 1024 for info in archive.infolist()):
            raise ValueError("Suite member exceeds the 16 MiB verification limit")
        contents = {name: archive.read(name) for name in names}
    report = load_json(contents["suite-report.json"])
    if not exact_integer(report["suiteReportVersion"], 1):
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
        for name in ("preview.zip", "duplicate-replay.jsonl", "event-time-ordering.jsonl"):
            (root / name).write_bytes(contents[name])
        expected = verified_suite_summary(root / "preview.zip", root / "duplicate-replay.jsonl",
                                          root / "event-time-ordering.jsonl")
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
    print(f"Verified 12 synthetic cases across four scenarios for source {report['sourceCommitSha']}.")
    print("Detection remains unknown; checksums do not authenticate the producer.")


if __name__ == "__main__":
    main()
