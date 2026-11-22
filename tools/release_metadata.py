"""Check or set the release version, date and DOI across the project metadata.

The Gradle version, CITATION.cff and the changelog must describe the same
release. ``check`` reports any disagreement, ``set`` changes all three together
on the day a maintainer decides to release, and ``doi`` records an archival
identifier once the archive has confirmed it. None of these commands creates a
tag, uploads anything or contacts any service.
"""

import argparse
import datetime
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = "https://github.com/syellanki09/intenttrace"
VERSION_PATTERN = r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.]+)?"
GRADLE_VERSION = re.compile(r'^version = "(' + VERSION_PATTERN + r')"$', re.MULTILINE)
CFF_VERSION = re.compile(r'^version: "([^"]*)"$', re.MULTILINE)
CFF_DATE = re.compile(r'^date-released: "([^"]*)"$', re.MULTILINE)
CFF_URL = re.compile(r'^url: "([^"]*)"$', re.MULTILINE)
CFF_DOI = re.compile(r'^doi: "([^"]*)"$', re.MULTILINE)
DOI_PATTERN = re.compile(r"10\.[0-9]{4,9}/\S+")


def _single(pattern, text, label):
    matches = pattern.findall(text)
    if len(matches) != 1:
        raise ValueError(f"Expected exactly one {label}, found {len(matches)}")
    return matches[0]


def read_metadata(root=ROOT):
    root = Path(root)
    gradle = (root / "build.gradle.kts").read_text(encoding="utf-8")
    citation = (root / "CITATION.cff").read_text(encoding="utf-8")
    changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    doi = CFF_DOI.findall(citation)
    return {
        "gradleVersion": _single(GRADLE_VERSION, gradle, "Gradle version"),
        "citationVersion": _single(CFF_VERSION, citation, "CITATION.cff version"),
        "citationDate": _single(CFF_DATE, citation, "CITATION.cff date-released"),
        "citationUrl": _single(CFF_URL, citation, "CITATION.cff url"),
        "doi": doi[0] if doi else None,
        "changelogVersions": re.findall(r"^## (" + VERSION_PATTERN + r") - ([0-9-]+)$", changelog, re.MULTILINE),
    }


def check(root=ROOT, tag=None):
    """Returns the metadata when it is consistent, otherwise raises ValueError naming every problem."""
    metadata = read_metadata(root)
    version = metadata["gradleVersion"]
    problems = []
    if metadata["citationVersion"] != version:
        problems.append(f"CITATION.cff version {metadata['citationVersion']} differs from Gradle version {version}")
    if metadata["citationUrl"] != f"{REPOSITORY}/releases/tag/v{version}":
        problems.append(f"CITATION.cff url does not point at the v{version} release")
    dated = dict(metadata["changelogVersions"])
    if version not in dated:
        problems.append(f"CHANGELOG.md has no dated section for {version}")
    elif dated[version] != metadata["citationDate"]:
        problems.append(f"CHANGELOG.md dates {version} {dated[version]}, CITATION.cff says {metadata['citationDate']}")
    if tag is not None and tag != f"v{version}":
        problems.append(f"Tag {tag} does not match version {version}")
    if metadata["doi"] is not None and DOI_PATTERN.fullmatch(metadata["doi"]) is None:
        problems.append(f"CITATION.cff doi {metadata['doi']} is not a DOI")
    if problems:
        raise ValueError("; ".join(problems))
    return metadata


def set_release(version, date, root=ROOT, today=None):
    """Moves Unreleased changelog entries to the version and updates Gradle and CITATION.cff together."""
    if re.fullmatch(VERSION_PATTERN, version) is None:
        raise ValueError(f"Not a release version: {version}")
    released = datetime.date.fromisoformat(date)
    if released > (today or datetime.date.today()):
        raise ValueError(f"Release date {date} is in the future; set it on the day of release")
    root = Path(root)
    current = read_metadata(root)
    if any(found == version for found, _ in current["changelogVersions"]):
        raise ValueError(f"CHANGELOG.md already has a section for {version}")
    changelog_path = root / "CHANGELOG.md"
    changelog = changelog_path.read_text(encoding="utf-8")
    if changelog.count("\n## Unreleased\n") != 1:
        raise ValueError("CHANGELOG.md needs exactly one Unreleased section")
    unreleased = changelog.split("\n## Unreleased\n", 1)[1].split("\n## ", 1)[0]
    if not unreleased.strip():
        raise ValueError("CHANGELOG.md has no Unreleased entries to release")

    gradle_path, citation_path = root / "build.gradle.kts", root / "CITATION.cff"
    gradle = GRADLE_VERSION.sub(f'version = "{version}"', gradle_path.read_text(encoding="utf-8"))
    citation = citation_path.read_text(encoding="utf-8")
    citation = CFF_VERSION.sub(f'version: "{version}"', citation)
    citation = CFF_DATE.sub(f'date-released: "{date}"', citation)
    citation = CFF_URL.sub(f'url: "{REPOSITORY}/releases/tag/v{version}"', citation)
    changelog = changelog.replace("\n## Unreleased\n", f"\n## Unreleased\n\n## {version} - {date}\n", 1)
    gradle_path.write_text(gradle, encoding="utf-8")
    citation_path.write_text(citation, encoding="utf-8")
    changelog_path.write_text(changelog, encoding="utf-8")
    return check(root)


def set_doi(doi, root=ROOT):
    """Records a DOI the archive has already confirmed. Replaces an existing one only if it is identical."""
    if DOI_PATTERN.fullmatch(doi) is None:
        raise ValueError(f"Not a DOI: {doi}")
    citation_path = Path(root) / "CITATION.cff"
    citation = citation_path.read_text(encoding="utf-8")
    existing = CFF_DOI.findall(citation)
    if existing:
        if existing == [doi]:
            return check(root)
        raise ValueError(f"CITATION.cff already records DOI {existing[0]}")
    citation = CFF_DATE.sub(lambda match: f'{match.group(0)}\ndoi: "{doi}"', citation, count=1)
    citation_path.write_text(citation, encoding="utf-8")
    return check(root)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    check_parser = commands.add_parser("check", help="Report whether the metadata agrees")
    check_parser.add_argument("--tag", help="Release tag that must match the version, for example v0.1.0-preview.2")
    set_parser = commands.add_parser("set", help="Set the version and release date together")
    set_parser.add_argument("--version", required=True)
    set_parser.add_argument("--date", required=True, help="Actual release date, YYYY-MM-DD")
    doi_parser = commands.add_parser("doi", help="Record a DOI confirmed by the archive")
    doi_parser.add_argument("doi")
    args = parser.parse_args(argv)
    try:
        if args.command == "check":
            metadata = check(ROOT, tag=args.tag)
        elif args.command == "set":
            metadata = set_release(args.version, args.date, ROOT)
        else:
            metadata = set_doi(args.doi, ROOT)
    except ValueError as error:
        print(f"Release metadata: {error}", file=sys.stderr)
        return 1
    doi = metadata["doi"] or "none recorded"
    print(f"Version {metadata['gradleVersion']}, released {metadata['citationDate']}, DOI {doi}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
