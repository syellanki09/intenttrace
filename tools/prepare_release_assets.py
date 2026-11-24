"""Prepare verified suite attachments and SHA256SUMS for maintainer review."""

import argparse
import hashlib
import re
import shutil
import tempfile
import zipfile
from pathlib import Path

from verify_results import exact_integer
from verify_suite import verify_suite


def file_sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def prepare_release_assets(suite, destination, source_commit):
    if not isinstance(source_commit, str) or re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", source_commit) is None:
        raise ValueError("Expected source commit must be a full Git object ID")
    suite, destination = Path(suite).resolve(), Path(destination).resolve()
    if destination.exists():
        raise FileExistsError(f"Choose a new attachment directory; output already exists: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Verify a private snapshot so every attachment comes from the exact checked bytes.
    with tempfile.TemporaryDirectory(prefix=".intenttrace-release-", dir=destination.parent) as directory:
        assets = Path(directory) / "assets"
        assets.mkdir()
        snapshot = assets / "suite.zip"
        shutil.copyfile(suite, snapshot)
        report = verify_suite(snapshot)
        if not exact_integer(report["suiteReportVersion"], 3):
            raise ValueError("Release attachments require a complete version-3 suite report")
        if report["sourceCommitSha"] != source_commit:
            raise ValueError("Suite source does not match the expected release commit")
        with zipfile.ZipFile(snapshot) as archive:
            for name in ("manifest.json", "suite-report.json"):
                (assets / name).write_bytes(archive.read(name))
        names = ("manifest.json", "suite-report.json", "suite.zip")
        checksums = "".join(f"{file_sha256(assets / name)}  {name}\n" for name in names)
        (assets / "SHA256SUMS").write_text(checksums, encoding="ascii")
        if destination.exists():
            raise FileExistsError(f"Attachment directory appeared during preparation: {destination}")
        assets.rename(destination)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("suite", type=Path)
    parser.add_argument("--source-commit", required=True, help="Full final source SHA that the evidence must match")
    parser.add_argument("--output", required=True, type=Path, help="New directory for the four attachment files")
    args = parser.parse_args()
    try:
        report = prepare_release_assets(args.suite, args.output, args.source_commit)
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile, RuntimeError) as error:
        parser.exit(1, f"Unable to prepare release attachments: {error}\n")
    print(f"Prepared verified attachments for source {report['sourceCommitSha']}.")
    print(f"Attachments: {args.output.resolve()}")
    print("Checksums establish consistency, not producer authenticity. Publication requires maintainer approval.")


if __name__ == "__main__":
    main()
