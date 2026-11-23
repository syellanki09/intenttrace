"""Check a local Maven staging directory and package it as a Central Portal bundle.

The build writes signed publications to ``build/central-staging``. This tool
checks that every published component carries what Maven Central requires
(POM metadata matching its coordinates, sources and Javadoc archives,
checksums that match each file's contents and, unless explicitly waived for a
dry run, a detached signature for every file) and that all
components share one namespace and one non-snapshot version. It then writes a
deterministic ZIP for upload. Signatures are verified separately with GnuPG,
because that needs the public key. Uploading is a separate maintainer step.
"""

import argparse
import hashlib
import re
import sys
import xml.etree.ElementTree as ElementTree
import zipfile
from pathlib import Path

POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
REQUIRED_POM_FIELDS = ("name", "description", "url", "licenses/m:license/m:name",
                       "developers/m:developer/m:name", "scm/m:url")
REQUIRED_CHECKSUMS = (".md5", ".sha1")
CHECKSUM_ALGORITHMS = {".md5": "md5", ".sha1": "sha1", ".sha256": "sha256", ".sha512": "sha512"}
SIGNATURE = ".asc"
SIDECARS = tuple(CHECKSUM_ALGORITHMS) + (SIGNATURE,)
FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


def _primary_files(directory):
    """Files that Maven Central validates, excluding checksums, signatures and repository metadata."""
    for path in sorted(directory.rglob("*")):
        if not path.is_file() or path.name.startswith("maven-metadata"):
            continue
        if path.name.endswith(SIDECARS):
            continue
        yield path


def _pom_text(pom, field):
    node = pom.find("m:" + field, POM_NAMESPACE)
    return node.text.strip() if node is not None and node.text else ""


def _check_checksums(primary):
    """Every checksum sidecar present must match the file's bytes; Gradle writes the bare hex digest."""
    content = primary.read_bytes()
    for suffix, algorithm in CHECKSUM_ALGORITHMS.items():
        sidecar = primary.with_name(primary.name + suffix)
        if not sidecar.is_file():
            continue
        recorded = sidecar.read_text(encoding="ascii", errors="replace").split()
        if not recorded or recorded[0].lower() != hashlib.new(algorithm, content).hexdigest():
            raise ValueError(f"{sidecar.name} does not match the contents of {primary.name}")


def check_staging(staging, namespace, version=None, require_signatures=True):
    """Returns the list of components, as (artifactId, version), or raises ValueError."""
    staging = Path(staging)
    root = staging / Path(*namespace.split("."))
    if not root.is_dir():
        raise ValueError(f"No publications under namespace {namespace} in {staging}")
    unexpected = [path for path in _primary_files(staging) if root not in path.parents]
    if unexpected:
        raise ValueError(f"Publication outside namespace {namespace}: {unexpected[0].relative_to(staging)}")
    components = []
    for version_dir in sorted(path for path in root.glob("*/*") if path.is_dir()):
        artifact, found_version = version_dir.parent.name, version_dir.name
        if found_version.endswith("-SNAPSHOT"):
            raise ValueError(f"Snapshot versions cannot be released: {artifact} {found_version}")
        if version is not None and found_version != version:
            raise ValueError(f"{artifact} has version {found_version}, expected {version}")
        stem = f"{artifact}-{found_version}"
        for suffix in (".jar", ".pom", "-sources.jar", "-javadoc.jar"):
            if not (version_dir / (stem + suffix)).is_file():
                raise ValueError(f"{artifact} {found_version} is missing {stem + suffix}")
        pom = ElementTree.parse(version_dir / f"{stem}.pom").getroot()
        if (_pom_text(pom, "groupId"), _pom_text(pom, "artifactId"), _pom_text(pom, "version")) != (
                namespace, artifact, found_version):
            raise ValueError(f"{stem}.pom does not match its directory coordinates")
        for field in REQUIRED_POM_FIELDS:
            if not _pom_text(pom, field):
                raise ValueError(f"{stem}.pom lacks required metadata: {field.replace('m:', '')}")
        for primary in _primary_files(version_dir):
            needed = REQUIRED_CHECKSUMS + ((SIGNATURE,) if require_signatures else ())
            for sidecar in needed:
                if not primary.with_name(primary.name + sidecar).is_file():
                    raise ValueError(f"{primary.name} is missing {primary.name + sidecar}")
            _check_checksums(primary)
        components.append((artifact, found_version))
    if not components:
        raise ValueError(f"No components found under namespace {namespace}")
    versions = {found for _, found in components}
    if len(versions) != 1:
        raise ValueError(f"Components disagree on version: {sorted(versions)}")
    return components


def write_bundle(staging, output, namespace, version=None, require_signatures=True):
    staging, output = Path(staging), Path(output)
    if output.exists():
        raise FileExistsError(f"Choose a new bundle path; output already exists: {output}")
    components = check_staging(staging, namespace, version, require_signatures)
    root = staging / Path(*namespace.split("."))
    members = sorted((path for path in root.rglob("*")
                      if path.is_file() and not path.name.startswith("maven-metadata")),
                     key=lambda path: path.relative_to(staging).as_posix())
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in members:
            info = zipfile.ZipInfo(path.relative_to(staging).as_posix(), FIXED_TIMESTAMP)
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())
    return components


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("staging", type=Path, help="Local Maven repository written by the build")
    parser.add_argument("--namespace", required=True, help="Maven groupId, for example io.github.syellanki09")
    parser.add_argument("--version", help="Version every component must have")
    parser.add_argument("--output", type=Path, help="New ZIP path; omit to only check the staging directory")
    parser.add_argument("--allow-unsigned", action="store_true",
                        help="Skip the signature check for a dry run; such a bundle cannot be published")
    args = parser.parse_args(argv)
    if args.version is not None and re.fullmatch(r"[0-9A-Za-z.+-]+", args.version) is None:
        parser.error("Version contains unexpected characters")
    try:
        if args.output is None:
            components = check_staging(args.staging, args.namespace, args.version, not args.allow_unsigned)
        else:
            components = write_bundle(args.staging, args.output, args.namespace, args.version,
                                      not args.allow_unsigned)
    except (ValueError, FileExistsError, ElementTree.ParseError) as error:
        print(f"Bundle check failed: {error}", file=sys.stderr)
        return 1
    signed = "unsigned dry run" if args.allow_unsigned else "signed"
    for artifact, found_version in components:
        print(f"{args.namespace}:{artifact}:{found_version} ({signed})")
    if args.output is not None:
        print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
