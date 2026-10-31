"""Verify a downloaded version-1 result bundle without extracting its members."""

import argparse
import hashlib
import re
import tempfile
import zipfile
from pathlib import Path

from verify_results import exact_integer, load_json, verify


def verify_bundle(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        required = {"manifest.json", "results.jsonl", "README.txt"}
        if len(names) != len(set(names)) or set(names) not in (required, required | {"scenario-config.json"}):
            raise ValueError("Bundle must contain unique, expected file names only")
        manifest = load_json(archive.read("manifest.json"))
        if not exact_integer(manifest["bundleVersion"], 1):
            raise ValueError("Unsupported bundle version")
        checked = {"results.jsonl"} | ({"scenario-config.json"} if "scenario-config.json" in names else set())
        if set(manifest["files"]) != checked:
            raise ValueError("Manifest must cover all evidence files")
        evidence = {}
        for name in checked:
            evidence[name] = archive.read(name)
            entry = manifest["files"][name]
            if not exact_integer(entry["bytes"], len(evidence[name])) or hashlib.sha256(evidence[name]).hexdigest() != entry["sha256"]:
                raise ValueError(f"Checksum or size mismatch: {name}")
    with tempfile.TemporaryDirectory(prefix="intenttrace-verify-") as directory:
        results = Path(directory) / "results.jsonl"
        results.write_bytes(evidence["results.jsonl"])
        records = verify(results)
    metadata = records[0]["reproduction"]
    if manifest["reproduction"] != metadata:
        raise ValueError("Manifest reproduction metadata disagrees with results")
    for field in ("scenarioName", "scenarioVersion", "sourceCommitSha"):
        if manifest[field] != metadata[field]:
            raise ValueError(f"Manifest disagrees with results: {field}")
    if re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", metadata["sourceCommitSha"]) is None:
        raise ValueError("Invalid source commit ID")
    configuration = metadata["configuration"]
    if configuration.get("sourceDirty") != "false":
        raise ValueError("Bundle must record a clean Git source")
    for field in ("runtime", "jdk", "operatingSystem"):
        if not isinstance(metadata[field], str) or not metadata[field].strip():
            raise ValueError(f"Missing runtime metadata: {field}")
    if "configurationVersion" in configuration:
        if "scenario-config.json" not in evidence:
            raise ValueError("Versioned scenario requires replay configuration")
        replay = load_json(evidence["scenario-config.json"])
        fields = ("configurationVersion", "contextId", "olderVersion", "latestVersion",
                  "olderCommittedAt", "latestCommittedAt", "decidedAt", "olderCategory", "latestCategory")
        expected = {key: configuration[key] for key in fields}
        for key in ("configurationVersion", "olderVersion", "latestVersion"):
            expected[key] = int(expected[key])
            if not exact_integer(replay[key], expected[key]):
                raise ValueError(f"Replay configuration must use an integer: {key}")
        if replay != expected:
            raise ValueError("Replay configuration disagrees with recorded inputs")
    elif "scenario-config.json" in evidence:
        raise ValueError("Unexpected replay configuration for unversioned inputs")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args()
    try:
        manifest = verify_bundle(args.bundle)
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile, RuntimeError) as error:
        parser.exit(1, f"Invalid reproduction bundle: {error}\n")
    print(f"Verified bundle for source {manifest['sourceCommitSha']}; checksums do not authenticate the producer.")


if __name__ == "__main__":
    main()
