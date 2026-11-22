import io
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from build_central_bundle import check_staging, main, write_bundle

NAMESPACE = "io.github.example"
POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <groupId>{group}</groupId>
  <artifactId>{artifact}</artifactId>
  <version>{version}</version>
  <name>Example</name>
  <description>Example library</description>
  <url>https://example.invalid/project</url>
  <licenses><license><name>Apache License, Version 2.0</name></license></licenses>
  <developers><developer><name>Example Developer</name></developer></developers>
  <scm><url>https://example.invalid/project</url></scm>
</project>
"""


class CentralBundleTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.staging = self.root / "staging"

    def component(self, artifact, version="1.0.0", group=NAMESPACE, signed=True, pom=POM):
        folder = self.staging / Path(*group.split(".")) / artifact / version
        folder.mkdir(parents=True)
        stem = f"{artifact}-{version}"
        files = {stem + ".pom": pom.format(group=group, artifact=artifact, version=version),
                 stem + ".jar": "jar", stem + "-sources.jar": "sources", stem + "-javadoc.jar": "javadoc",
                 stem + ".module": "{}"}
        for name, content in files.items():
            (folder / name).write_text(content)
            for sidecar in (".md5", ".sha1", ".sha256") + ((".asc",) if signed else ()):
                (folder / (name + sidecar)).write_text("x")
        metadata = folder.parent / "maven-metadata.xml"
        metadata.write_text("<metadata/>")
        (folder.parent / "maven-metadata.xml.md5").write_text("x")
        return folder

    def test_complete_signed_components_are_bundled_without_repository_metadata(self):
        self.component("intenttrace-detector")
        self.component("intenttrace-detector-otel")
        output = self.root / "bundle.zip"
        components = write_bundle(self.staging, output, NAMESPACE, "1.0.0")
        self.assertEqual([("intenttrace-detector", "1.0.0"), ("intenttrace-detector-otel", "1.0.0")], components)
        with zipfile.ZipFile(output) as archive:
            names = archive.namelist()
            self.assertEqual(sorted(names), names)
            self.assertFalse(any("maven-metadata" in name for name in names))
            self.assertIn("io/github/example/intenttrace-detector/1.0.0/intenttrace-detector-1.0.0.jar.asc", names)
            self.assertTrue(all(info.date_time == (1980, 1, 1, 0, 0, 0) for info in archive.infolist()))

    def test_bundles_are_byte_for_byte_repeatable(self):
        self.component("intenttrace-detector")
        write_bundle(self.staging, self.root / "a.zip", NAMESPACE)
        write_bundle(self.staging, self.root / "b.zip", NAMESPACE)
        self.assertEqual((self.root / "a.zip").read_bytes(), (self.root / "b.zip").read_bytes())

    def test_missing_signatures_fail_unless_explicitly_waived_for_a_dry_run(self):
        self.component("intenttrace-detector", signed=False)
        with self.assertRaisesRegex(ValueError, r"\.asc"):
            check_staging(self.staging, NAMESPACE)
        self.assertEqual([("intenttrace-detector", "1.0.0")],
                         check_staging(self.staging, NAMESPACE, require_signatures=False))

    def test_missing_required_archives_and_checksums_fail(self):
        folder = self.component("intenttrace-detector")
        (folder / "intenttrace-detector-1.0.0-javadoc.jar").unlink()
        with self.assertRaisesRegex(ValueError, "javadoc"):
            check_staging(self.staging, NAMESPACE)
        (folder / "intenttrace-detector-1.0.0-javadoc.jar").write_text("javadoc")
        (folder / "intenttrace-detector-1.0.0.jar.sha1").unlink()
        with self.assertRaisesRegex(ValueError, r"\.sha1"):
            check_staging(self.staging, NAMESPACE)

    def test_incomplete_pom_metadata_fails(self):
        self.component("intenttrace-detector", pom=POM.replace("<scm><url>https://example.invalid/project</url></scm>", ""))
        with self.assertRaisesRegex(ValueError, "scm/url"):
            check_staging(self.staging, NAMESPACE)

    def test_versions_namespaces_and_snapshots_are_enforced(self):
        self.component("intenttrace-detector", "1.0.0")
        self.component("intenttrace-detector-otel", "1.0.1")
        with self.assertRaisesRegex(ValueError, "disagree"):
            check_staging(self.staging, NAMESPACE)
        with self.assertRaisesRegex(ValueError, "expected 1.0.0"):
            check_staging(self.staging, NAMESPACE, "1.0.0")
        self.setUp()
        self.component("intenttrace-detector", "1.0.0-SNAPSHOT")
        with self.assertRaisesRegex(ValueError, "Snapshot"):
            check_staging(self.staging, NAMESPACE)
        self.setUp()
        self.component("intenttrace-detector")
        self.component("other", group="io.example.other")
        with self.assertRaisesRegex(ValueError, "outside namespace"):
            check_staging(self.staging, NAMESPACE)
        with self.assertRaisesRegex(ValueError, "No publications"):
            check_staging(self.staging, "io.github.missing")

    def test_existing_output_is_never_overwritten(self):
        self.component("intenttrace-detector")
        output = self.root / "bundle.zip"
        output.write_text("keep")
        with self.assertRaises(FileExistsError):
            write_bundle(self.staging, output, NAMESPACE)
        self.assertEqual("keep", output.read_text())

    def test_command_line_reports_components_and_failures(self):
        self.component("intenttrace-detector", signed=False)
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            self.assertEqual(1, main([str(self.staging), "--namespace", NAMESPACE]))
            self.assertEqual(0, main([str(self.staging), "--namespace", NAMESPACE, "--allow-unsigned"]))
        self.assertIn("Bundle check failed", err.getvalue())
        self.assertIn("io.github.example:intenttrace-detector:1.0.0 (unsigned dry run)", out.getvalue())


if __name__ == "__main__":
    unittest.main()
