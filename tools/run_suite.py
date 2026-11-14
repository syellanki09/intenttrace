"""Build, run, and independently verify all four IntentTrace demonstrations."""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

from run_preview import collect_preview
from verify_suite import verified_suite_summary, verify_suite


def collect_suite(stale, contract, replay, events, destination, source_commit):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    collect_preview(stale, contract, destination / "preview", source_commit)
    for source, name in [(destination / "preview/preview.zip", "preview.zip"),
                         (replay, "duplicate-replay.jsonl"), (events, "event-time-ordering.jsonl")]:
        shutil.copyfile(source, destination / name)
    report = verified_suite_summary(destination / "preview.zip", destination / "duplicate-replay.jsonl",
                                    destination / "event-time-ordering.jsonl")
    files = ["preview.zip", "duplicate-replay.jsonl", "event-time-ordering.jsonl"]
    if (destination / "build.log").is_file():
        files.append("build.log")
    report["files"] = {name: {"sha256": hashlib.sha256((destination / name).read_bytes()).hexdigest(),
                              "bytes": (destination / name).stat().st_size} for name in files}
    (destination / "suite-report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (destination / "README.txt").write_text(
        f"IntentTrace synthetic scenario suite\nSource: {source_commit}\n"
        "Run from a clean checkout with JDK 17 and Python 3.9+: python3 tools/run_suite.py\n"
        "Verify offline with Python only: python3 tools/verify_suite.py suite.zip\n"
        "preview.zip preserves the original five-case format; other JSONL files add seven cases.\n"
        "All detection observations remain unknown. Checksums establish consistency, not authenticity.\n"
        "Runtime metadata and logs can vary between reproductions.\n", encoding="utf-8")
    archive = destination / "suite.zip"
    try:
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
            for name in files + ["suite-report.json", "README.txt"]:
                output.write(destination / name, arcname=name)
        verify_suite(archive)
    except Exception:
        archive.unlink(missing_ok=True)
        (destination / "suite-report.json").unlink(missing_ok=True)
        raise
    return report


def run_suite(repository):
    repository = Path(repository).resolve()

    def git(*args):
        return subprocess.check_output(["git", *args], cwd=repository, text=True).strip()

    source = git("rev-parse", "HEAD")
    if git("status", "--porcelain"):
        raise ValueError("Start from a clean Git checkout; commit or set aside changes first")
    parent = repository / "build/suite"
    parent.mkdir(parents=True, exist_ok=True)
    destination = Path(tempfile.mkdtemp(prefix=source[:12] + "-", dir=parent))
    gradle = repository / ("gradlew.bat" if os.name == "nt" else "gradlew")
    try:
        with (destination / "build.log").open("w", encoding="utf-8") as log:
            commands = [[str(gradle), "clean", "test", ":benchmarks:run", ":benchmarks:runContractScenario",
                         ":benchmarks:runReplayScenario", ":benchmarks:runEventTimeScenario", "--no-daemon"],
                        [sys.executable, "-m", "unittest", "discover", "-s", "tools", "-p", "test_*.py"]]
            for command in commands:
                print("Running: " + " ".join(command), flush=True)
                subprocess.run(command, cwd=repository, stdout=log, stderr=subprocess.STDOUT, check=True)
        if git("rev-parse", "HEAD") != source or git("status", "--porcelain"):
            raise ValueError("Source changed during suite reproduction")
        results = repository / "benchmarks/build/results"
        report = collect_suite(results / "stale-context/results.jsonl", results / "contract-unit-drift/results.jsonl",
                               results / "duplicate-replay/results.jsonl", results / "event-time-ordering/results.jsonl",
                               destination, source)
    except Exception:
        (destination / "suite.zip").unlink(missing_ok=True)
        (destination / "suite-report.json").unlink(missing_ok=True)
        print(f"Suite incomplete. Diagnostics: {destination / 'build.log'}", file=sys.stderr)
        raise
    for scenario, cases in report["scenarios"].items():
        print(f"{scenario}: " + ", ".join(row["status"] for row in cases))
    print("Verified 12 synthetic cases. Detection observations remain unknown.")
    print(f"Suite: {destination / 'suite.zip'}")
    print(f"Summary: {destination / 'suite-report.json'}")
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    try:
        run_suite(Path(__file__).resolve().parents[1])
    except (ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile, RuntimeError) as error:
        parser.exit(1, f"Unable to prepare suite: {error}\n")


if __name__ == "__main__":
    main()
