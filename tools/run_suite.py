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
from prepare_release_assets import prepare_release_assets
from verify_suite import SCENARIOS, verified_manifest, verified_suite_summary, verify_suite


def deterministic_zip(destination, files):
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)


def run_logged(command, repository, log, label):
    print(f"Running {label}: " + " ".join(command), flush=True)
    log.write(f"\nRunning {label}: " + " ".join(command) + "\n")
    log.flush()
    process = subprocess.Popen(command, cwd=repository, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    try:
        for line in process.stdout:
            print(line, end="", flush=True)
            log.write(line)
        code = process.wait()
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.terminate()
            process.wait()
    if code:
        message = f"{label} failed (exit {code})"
        print(message, file=sys.stderr, flush=True)
        log.write(message + "\n")
        raise subprocess.CalledProcessError(code, command, stderr=f"{label} failed")


def verify_scenario(scenario, path):
    verifier = next(check for name, _, check in SCENARIOS if name == scenario)
    try:
        return verifier(path)
    except (ValueError, KeyError, TypeError, OSError) as error:
        raise ValueError(f"{scenario}: verification failed: {error}") from error


def collect_suite(stale, contract, replay, events, destination, source_commit):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    collect_preview(stale, contract, destination / "preview", source_commit)
    # Normalize ZIP metadata without changing any evidence bytes or the preview schema.
    preview_path = destination / "preview/preview.zip"
    with zipfile.ZipFile(preview_path) as archive:
        preview_files = {name: archive.read(name) for name in archive.namelist()}
    deterministic_zip(preview_path, preview_files)
    for source, name in [(preview_path, "preview.zip"), (stale, "stale-context.jsonl"),
                         (contract, "contract-unit-drift.jsonl"),
                         (replay, "duplicate-replay.jsonl"), (events, "event-time-ordering.jsonl")]:
        shutil.copyfile(source, destination / name)
    manifest = verified_manifest(destination)
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    report = verified_suite_summary(destination / "preview.zip", destination / "duplicate-replay.jsonl",
                                    destination / "event-time-ordering.jsonl")
    report["suiteReportVersion"] = 2
    files = ["manifest.json", "preview.zip", "stale-context.jsonl", "contract-unit-drift.jsonl",
             "duplicate-replay.jsonl", "event-time-ordering.jsonl"]
    if (destination / "build.log").is_file():
        files.append("build.log")
    report["files"] = {name: {"sha256": hashlib.sha256((destination / name).read_bytes()).hexdigest(),
                              "bytes": (destination / name).stat().st_size} for name in files}
    (destination / "suite-report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (destination / "README.txt").write_text(
        f"IntentTrace synthetic scenario suite\nSource: {source_commit}\n"
        "Run from a clean checkout with JDK 17 and Python 3.9+: python3 tools/run_suite.py\n"
        "Verify offline with Python only: python3 tools/verify_suite.py suite.zip\n"
        "manifest.json indexes all four raw JSONL files with record counts and SHA-256 hashes.\n"
        "preview.zip preserves the original five-case format.\n"
        "All detection observations remain unknown. Checksums establish consistency, not authenticity.\n"
        "Runtime metadata and logs can vary between reproductions.\n", encoding="utf-8")
    archive = destination / "suite.zip"
    try:
        deterministic_zip(archive, {name: (destination / name).read_bytes()
                                    for name in files + ["suite-report.json", "README.txt"]})
        verify_suite(archive)
    except Exception:
        archive.unlink(missing_ok=True)
        (destination / "suite-report.json").unlink(missing_ok=True)
        (destination / "manifest.json").unlink(missing_ok=True)
        raise
    return report


def run_suite(repository, package_only=False, release_assets=None):
    repository = Path(repository).resolve()

    def git(*args):
        return subprocess.check_output(["git", *args], cwd=repository, text=True).strip()

    source = git("rev-parse", "HEAD")
    if git("status", "--porcelain"):
        raise ValueError("Start from a clean Git checkout; commit or set aside changes first")
    assets = (repository / Path(release_assets)).resolve() if release_assets is not None else None
    if assets is not None and assets.exists():
        raise FileExistsError(f"Choose a new release attachment directory: {assets}")
    parent = repository / "build/suite"
    parent.mkdir(parents=True, exist_ok=True)
    destination = Path(tempfile.mkdtemp(prefix=source[:12] + "-", dir=parent))
    gradle = repository / ("gradlew.bat" if os.name == "nt" else "gradlew")
    results = repository / "benchmarks/build/results"
    try:
        if not package_only:
            with (destination / "build.log").open("w", encoding="utf-8") as log:
                run_logged([str(gradle), "clean", "test", "--no-daemon"], repository, log, "Kotlin tests")
                for task, scenario in [(":benchmarks:run", "stale-context"),
                                       (":benchmarks:runContractScenario", "contract-unit-drift"),
                                       (":benchmarks:runReplayScenario", "duplicate-replay"),
                                       (":benchmarks:runEventTimeScenario", "event-time-ordering")]:
                    run_logged([str(gradle), task, "--no-daemon"], repository, log, scenario)
                    verify_scenario(scenario, results / scenario / "results.jsonl")
                run_logged([sys.executable, "-m", "unittest", "discover", "-s", "tools", "-p", "test_*.py"],
                           repository, log, "Python tests")
        if git("rev-parse", "HEAD") != source or git("status", "--porcelain"):
            raise ValueError("Source changed during suite reproduction")
        report = collect_suite(results / "stale-context/results.jsonl", results / "contract-unit-drift/results.jsonl",
                               results / "duplicate-replay/results.jsonl", results / "event-time-ordering/results.jsonl",
                               destination, source)
        if assets is not None:
            prepare_release_assets(destination / "suite.zip", assets, source)
    except Exception:
        (destination / "suite.zip").unlink(missing_ok=True)
        (destination / "suite-report.json").unlink(missing_ok=True)
        (destination / "manifest.json").unlink(missing_ok=True)
        diagnostics = destination / "build.log"
        print(f"Suite incomplete. Diagnostics: {diagnostics if diagnostics.exists() else destination}", file=sys.stderr)
        raise
    for scenario, cases in report["scenarios"].items():
        print(f"{scenario}: " + ", ".join(row["status"] for row in cases))
    print("Verified 12 synthetic cases. Detection observations remain unknown.")
    print(f"Suite: {destination / 'suite.zip'}")
    print(f"Summary: {destination / 'suite-report.json'}")
    print(f"Manifest: {destination / 'manifest.json'}")
    if assets is not None:
        print(f"Release attachments: {assets}")
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package-only", action="store_true",
                        help="Verify and package existing scenario outputs without executing builds or tests (for CI)")
    parser.add_argument("--release-assets", type=Path,
                        help="Also prepare verified release attachments in a new directory, relative to the repository")
    args = parser.parse_args()
    try:
        run_suite(Path(__file__).resolve().parents[1], package_only=args.package_only,
                  release_assets=args.release_assets)
    except (ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile, RuntimeError) as error:
        detail = error.stderr if isinstance(error, subprocess.CalledProcessError) and error.stderr else str(error)
        parser.exit(1, f"Unable to prepare suite: {detail}\n")


if __name__ == "__main__":
    main()
