"""Package verified clean-checkout scenario output with checksums and reproduction metadata."""

import argparse
import hashlib
import json
import re
import zipfile
from pathlib import Path

from verify_results import verify


def bundle(results, destination):
    results, destination = Path(results), Path(destination)
    if results.resolve() == destination.resolve():
        raise ValueError("Bundle output must not overwrite the raw results")
    records = verify(results)
    metadata = records[0]["reproduction"]
    if metadata["configuration"].get("sourceDirty") != "false":
        raise ValueError("Bundles require runner output from a verified clean Git checkout")
    commit = metadata["sourceCommitSha"]
    if not isinstance(commit, str) or re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", commit) is None:
        raise ValueError("A full source commit ID is required")
    for field in ("runtime", "jdk", "operatingSystem"):
        if not isinstance(metadata[field], str) or not metadata[field].strip():
            raise ValueError(f"Missing reproduction metadata: {field}")
    raw = results.read_bytes()
    configuration = metadata["configuration"]
    replay = None
    if "configurationVersion" in configuration:
        fields = ("configurationVersion", "contextId", "olderVersion", "latestVersion",
                  "olderCommittedAt", "latestCommittedAt", "decidedAt", "olderCategory", "latestCategory")
        replay = {key: configuration[key] for key in fields}
        for key in ("configurationVersion", "olderVersion", "latestVersion"):
            replay[key] = int(replay[key])
    replay_bytes = (json.dumps(replay, indent=2, sort_keys=True) + "\n").encode() if replay else None
    manifest = {
        "bundleVersion": 1,
        "scenarioName": metadata["scenarioName"],
        "scenarioVersion": metadata["scenarioVersion"],
        "sourceCommitSha": commit,
        "reproduction": metadata,
        "files": {"results.jsonl": {"sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}},
    }
    if replay_bytes is not None:
        manifest["files"]["scenario-config.json"] = {
            "sha256": hashlib.sha256(replay_bytes).hexdigest(), "bytes": len(replay_bytes)
        }
    command = "./gradlew :benchmarks:run --no-daemon"
    if replay is not None:
        command += ' --args="--config /absolute/path/to/extracted/scenario-config.json"'
    instructions = (
        "IntentTrace synthetic stale-context reproduction bundle\n"
        f"Source commit: {commit}\n"
        "Clone https://github.com/syellanki09/intenttrace.git and check out that commit.\n"
        "With JDK 17 and Python 3.9+, run from the checkout:\n"
        f"{command}\n"
        "python3 tools/verify_results.py benchmarks/build/results/stale-context/results.jsonl\n"
        "The two records show CONTROL=SATISFIED and INJECTED=VIOLATED.\n"
        "Detection is unknown, not successful detection. Runtime metadata may differ.\n"
        "Checksums detect file changes; this unsigned bundle does not authenticate its producer.\n"
        "This is CI reproduction evidence, not a tagged reference release.\n"
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    files = [
        ("results.jsonl", raw),
        ("manifest.json", (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()),
        ("README.txt", instructions.encode()),
    ]
    if replay_bytes is not None:
        files.append(("scenario-config.json", replay_bytes))
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in files:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        bundle(args.results, args.output)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Unable to bundle results: {error}\n")
    print(f"Wrote verified reproduction bundle to {args.output}")


if __name__ == "__main__":
    main()
