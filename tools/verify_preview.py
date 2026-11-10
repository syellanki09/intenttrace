"""Independently verify a downloaded preview pack without Gradle or extraction."""

import argparse
import hashlib
import json
import tempfile
import zipfile
from pathlib import Path

from verify_bundle import verify_bundle
from verify_contract_results import verify_contract_results
from verify_results import exact_integer, load_json, verify


def verify_preview(path):
    required = {"report.json", "README.txt", "stale-context.jsonl",
                "contract-unit-drift.jsonl", "stale-context-bundle.zip"}
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) not in (required, required | {"build.log"}):
            raise ValueError("Preview must contain unique, expected file names only")
        # Bound reads of untrusted compressed attachments before allocating their contents.
        if any(info.file_size > 16 * 1024 * 1024 for info in archive.infolist()):
            raise ValueError("Preview member exceeds the 16 MiB verification limit")
        contents = {name: archive.read(name) for name in names}
    report = load_json(contents["report.json"])
    if not exact_integer(report["previewReportVersion"], 1):
        raise ValueError("Unsupported preview report version")
    evidence = set(names) - {"report.json", "README.txt"}
    if set(report["files"]) != evidence:
        raise ValueError("Report must cover all evidence files")
    for name in evidence:
        entry = report["files"][name]
        if (not exact_integer(entry["bytes"], len(contents[name]))
                or entry["sha256"] != hashlib.sha256(contents[name]).hexdigest()):
            raise ValueError(f"Checksum or size mismatch: {name}")
    with tempfile.TemporaryDirectory(prefix="intenttrace-preview-verify-") as directory:
        root = Path(directory)
        for name in ("stale-context.jsonl", "contract-unit-drift.jsonl", "stale-context-bundle.zip"):
            (root / name).write_bytes(contents[name])
        stale = verify(root / "stale-context.jsonl")
        contracts = verify_contract_results(root / "contract-unit-drift.jsonl")
        with zipfile.ZipFile(root / "stale-context-bundle.zip") as nested:
            if any(info.file_size > 16 * 1024 * 1024 for info in nested.infolist()):
                raise ValueError("Nested bundle member exceeds the 16 MiB verification limit")
            if nested.read("results.jsonl") != contents["stale-context.jsonl"]:
                raise ValueError("Nested bundle disagrees with outer context evidence")
        manifest = verify_bundle(root / "stale-context-bundle.zip")
    for record in stale + contracts:
        metadata = record["reproduction"]
        if (metadata["sourceCommitSha"] != report["sourceCommitSha"]
                or metadata["configuration"].get("sourceDirty") != "false"):
            raise ValueError("All evidence must identify the same clean source commit")
        runtime = {key: metadata[key] for key in ("runtime", "jdk", "operatingSystem")}
        if runtime != report["runtime"]:
            raise ValueError("Report runtime disagrees with evidence")
    if manifest["sourceCommitSha"] != report["sourceCommitSha"]:
        raise ValueError("Nested source disagrees with report")
    summaries = {
        "staleContext": [{"execution": row["execution"], "status": row["evaluation"]["status"],
                          "detected": row["evaluation"]["detected"]} for row in stale],
        "contractUnitDrift": [{"caseId": row["caseId"], "status": row["status"],
                               "detected": row["detected"]} for row in contracts],
    }
    for key, expected in summaries.items():
        if json.dumps(report[key], sort_keys=True) != json.dumps(expected, sort_keys=True):
            raise ValueError(f"Report summary disagrees with independently verified evidence: {key}")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("preview", type=Path)
    args = parser.parse_args()
    try:
        report = verify_preview(args.preview)
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile, RuntimeError) as error:
        parser.exit(1, f"Invalid preview pack: {error}\n")
    print(f"Verified five synthetic cases for source {report['sourceCommitSha']}.")
    print("Ground truth is not system detection; checksums do not authenticate the producer.")


if __name__ == "__main__":
    main()
