import datetime
import io
import shutil
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

import release_metadata
from release_metadata import ROOT, check, read_metadata, set_doi, set_release

TODAY = datetime.date(2026, 12, 1)


class ReleaseMetadataTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        for name in ("build.gradle.kts", "CITATION.cff", "CHANGELOG.md"):
            shutil.copyfile(ROOT / name, self.root / name)

    def test_the_repository_metadata_is_consistent(self):
        metadata = check(ROOT)
        self.assertEqual(metadata["gradleVersion"], metadata["citationVersion"])

    def test_set_updates_every_file_together_and_keeps_history(self):
        before = (self.root / "CHANGELOG.md").read_text()
        metadata = set_release("0.1.0-preview.2", "2026-11-30", self.root, today=TODAY)
        self.assertEqual("0.1.0-preview.2", metadata["gradleVersion"])
        self.assertEqual("2026-11-30", metadata["citationDate"])
        self.assertTrue(metadata["citationUrl"].endswith("/releases/tag/v0.1.0-preview.2"))
        changelog = (self.root / "CHANGELOG.md").read_text()
        self.assertIn("## Unreleased\n\n## 0.1.0-preview.2 - 2026-11-30\n", changelog)
        self.assertEqual(before.count("## 0.1.0-preview.1 - 2026-11-09"), changelog.count("## 0.1.0-preview.1 - 2026-11-09"))
        self.assertIn('version = "0.1.0-preview.2"', (self.root / "build.gradle.kts").read_text())
        check(self.root, tag="v0.1.0-preview.2")

    def test_future_dates_repeated_versions_and_bad_versions_are_refused(self):
        with self.assertRaisesRegex(ValueError, "future"):
            set_release("0.1.0-preview.2", "2026-12-02", self.root, today=TODAY)
        with self.assertRaisesRegex(ValueError, "already has a section"):
            set_release("0.1.0-preview.1", "2026-11-30", self.root, today=TODAY)
        with self.assertRaisesRegex(ValueError, "Not a release version"):
            set_release("next", "2026-11-30", self.root, today=TODAY)
        self.assertEqual(read_metadata(ROOT)["gradleVersion"], read_metadata(self.root)["gradleVersion"])

    def test_disagreements_are_all_reported(self):
        citation = self.root / "CITATION.cff"
        citation.write_text(citation.read_text().replace('version: "0.1.0-preview.1"', 'version: "9.9.9"'))
        with self.assertRaises(ValueError) as raised:
            check(self.root, tag="v0.2.0")
        message = str(raised.exception)
        self.assertIn("differs from Gradle version", message)
        self.assertIn("does not match version", message)

    def test_doi_is_recorded_once_and_must_look_like_a_doi(self):
        set_doi("10.5281/zenodo.1234567", self.root)
        self.assertEqual("10.5281/zenodo.1234567", read_metadata(self.root)["doi"])
        set_doi("10.5281/zenodo.1234567", self.root)
        with self.assertRaisesRegex(ValueError, "already records"):
            set_doi("10.5281/zenodo.7654321", self.root)
        with self.assertRaisesRegex(ValueError, "Not a DOI"):
            set_doi("zenodo-1234567", self.root)

    def test_command_line_check_uses_the_selected_root(self):
        citation = self.root / "CITATION.cff"
        citation.write_text(citation.read_text().replace('version: "0.1.0-preview.1"', 'version: "9.9.9"'))
        err = io.StringIO()
        with patch.object(release_metadata, "ROOT", self.root), redirect_stderr(err):
            self.assertEqual(1, release_metadata.main(["check"]))
        self.assertIn("differs from Gradle version", err.getvalue())

    def test_command_line_check(self):
        out, err = io.StringIO(), io.StringIO()
        with patch.object(release_metadata, "ROOT", self.root), redirect_stdout(out), redirect_stderr(err):
            self.assertEqual(0, release_metadata.main(["check"]))
            self.assertEqual(1, release_metadata.main(["check", "--tag", "v0.0.1"]))
        self.assertIn("Version 0.1.0-preview.1", out.getvalue())
        self.assertIn("does not match", err.getvalue())


if __name__ == "__main__":
    unittest.main()
