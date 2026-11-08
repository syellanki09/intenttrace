"""Build, run, independently verify, and collect both IntentTrace demonstrations."""

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

from bundle_results import bundle
from verify_bundle import verify_bundle
from verify_contract_results import verify_contract_results
from verify_results import verify


def collect_preview(stale_results, contract_results, destination, source_commit):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    stale = destination / "stale-context.jsonl"
    contract = destination / "contract-unit-drift.jsonl"
    shutil.copyfile(stale_results, stale)
    shutil.copyfile(contract_results, contract)
    stale_records, contract_records = verify(stale), verify_contract_results(contract)
    metadata = [stale_records[0]["reproduction"], contract_records[0]["reproduction"]]
    for record in metadata:
        if record["sourceCommitSha"] != source_commit or record["configuration"].get("sourceDirty") != "false":
            raise ValueError("Preview evidence must come from the selected clean source commit")
    for field in ("runtime", "jdk", "operatingSystem"):
        if metadata[0][field] != metadata[1][field]:
            raise ValueError(f"Scenario runtime mismatch: {field}")
    bundle(stale, destination / "stale-context-bundle.zip")
    verify_bundle(destination / "stale-context-bundle.zip")
    files = ["stale-context.jsonl", "contract-unit-drift.jsonl", "stale-context-bundle.zip"]
    if (destination / "build.log").is_file():
        files.append("build.log")
    report = {
        "previewReportVersion": 1,
        "sourceCommitSha": source_commit,
        "runtime": {field: metadata[0][field] for field in ("runtime", "jdk", "operatingSystem")},
        "staleContext": [{"execution": record["execution"], "status": record["evaluation"]["status"],
                           "detected": record["evaluation"]["detected"]} for record in stale_records],
        "contractUnitDrift": [{"caseId": record["caseId"], "status": record["status"],
                               "detected": record["detected"]} for record in contract_records],
        "files": {name: {"sha256": hashlib.sha256((destination / name).read_bytes()).hexdigest(),
                          "bytes": (destination / name).stat().st_size} for name in files},
    }
    (destination / "report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (destination / "README.txt").write_text(
        "IntentTrace experimental preview review pack (not a release).\n"
        f"Verified source: {source_commit}\n"
        "Clone https://github.com/syellanki09/intenttrace.git and check out this commit.\n"
        "With JDK 17, Python 3.9+, and dependency network access, run:\n"
        "python3 tools/run_preview.py\n"
        "report.json summarizes independently checked synthetic outcomes and hashes raw evidence.\n"
        "Detection remains unknown. Passing does not demonstrate production prevalence.\n"
        "Runtime details may vary across reproductions. Hashes do not authenticate the producer.\n"
        "See docs/preview-walkthrough.md and CONTRIBUTING.md to give feedback.\n", encoding="utf-8")
    archive_path = destination / "preview.zip"
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in files + ["report.json", "README.txt"]:
            archive.write(destination / name, arcname=name)
    return report


def run_preview(repository):
    repository = Path(repository).resolve()
    source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip()
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=repository, text=True).strip():
        raise ValueError("Start from a clean Git checkout; commit or set aside your changes first")
    parent = repository / "build" / "preview"
    parent.mkdir(parents=True, exist_ok=True)
    destination = Path(tempfile.mkdtemp(prefix=source[:12] + "-", dir=parent))
    gradle = repository / ("gradlew.bat" if os.name == "nt" else "gradlew")
    try:
        with (destination / "build.log").open("w", encoding="utf-8") as log:
            for command in [
                [str(gradle), "clean", "test", ":benchmarks:run", ":benchmarks:runContractScenario", "--no-daemon"],
                [sys.executable, "-m", "unittest", "discover", "-s", "tools", "-p", "test_*.py"],
            ]:
                print("Running: " + " ".join(command), flush=True)
                subprocess.run(command, cwd=repository, stdout=log, stderr=subprocess.STDOUT, check=True)
        if subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip() != source:
            raise ValueError("Source commit changed during reproduction")
        if subprocess.check_output(["git", "status", "--porcelain"], cwd=repository, text=True).strip():
            raise ValueError("Working tree changed during reproduction")
        collect_preview(repository / "benchmarks/build/results/stale-context/results.jsonl",
                        repository / "benchmarks/build/results/contract-unit-drift/results.jsonl",
                        destination, source)
    except Exception:
        (destination / "report.json").unlink(missing_ok=True)
        (destination / "preview.zip").unlink(missing_ok=True)
        print(f"Preview incomplete. Diagnostics: {destination / 'build.log'}", file=sys.stderr)
        raise
    print("Verified: stale control SATISFIED; stale injection VIOLATED.")
    print("Verified: contract upgrade SATISFIED; both unit-drift cases VIOLATED.")
    print("Detection observations remain unknown; no detection rate is claimed.")
    print(f"Review pack: {destination / 'preview.zip'}")
    print(f"Summary: {destination / 'report.json'}")
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    try:
        run_preview(Path(__file__).resolve().parents[1])
    except (ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Unable to prepare preview: {error}\n")


if __name__ == "__main__":
    main()
