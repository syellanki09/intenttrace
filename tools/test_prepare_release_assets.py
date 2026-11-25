import hashlib
import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

import test_suite as fixtures
from prepare_release_assets import main, prepare_release_assets
from verify_suite import verify_suite


class ReleaseAssetsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.SuiteTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.collect()
        self.output = self.fixture.destination.parent / "release-assets"

    def test_verified_suite_and_sidecars_are_preserved_exactly(self):
        report = prepare_release_assets(self.fixture.archive, self.output, "a" * 40)
        self.assertEqual({"suite.zip", "manifest.json", "suite-report.json", "SHA256SUMS"},
                         {path.name for path in self.output.iterdir()})
        self.assertEqual(self.fixture.archive.read_bytes(), (self.output / "suite.zip").read_bytes())
        contents = self.fixture.contents()
        for name in ("manifest.json", "suite-report.json"):
            self.assertEqual(contents[name], (self.output / name).read_bytes())
        self.assertEqual(report, verify_suite(self.output / "suite.zip"))
        lines = (self.output / "SHA256SUMS").read_text().splitlines()
        self.assertEqual(["manifest.json", "suite-report.json", "suite.zip"],
                         [line.split("  ")[1] for line in lines])
        for line in lines:
            digest, name = line.split("  ")
            self.assertEqual(digest, hashlib.sha256((self.output / name).read_bytes()).hexdigest())

    def test_wrong_or_abbreviated_source_cannot_produce_attachments(self):
        for source in ("b" * 40, "a" * 12, "not-a-commit"):
            with self.subTest(source=source), self.assertRaises(ValueError):
                prepare_release_assets(self.fixture.archive, self.output, source)
            self.assertFalse(self.output.exists())

    def test_tampered_evidence_is_rejected_without_partial_output(self):
        contents = self.fixture.contents()
        contents["duplicate-replay.jsonl"] += b" "
        self.fixture.write(contents)
        with self.assertRaises(ValueError):
            prepare_release_assets(self.fixture.archive, self.output, "a" * 40)
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.output.parent.glob(".intenttrace-release-*")))

    def test_existing_output_is_never_overwritten(self):
        self.output.mkdir()
        marker = self.output / "keep.txt"
        marker.write_text("existing review material", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            prepare_release_assets(self.fixture.archive, self.output, "a" * 40)
        self.assertEqual("existing review material", marker.read_text())
        self.assertEqual([marker], list(self.output.iterdir()))

    def test_legacy_suite_requires_new_indexed_evidence_for_release_attachments(self):
        contents = self.fixture.contents()
        report = json.loads(contents["suite-report.json"])
        for name in ("manifest.json", "stale-context.jsonl", "contract-unit-drift.jsonl",
                     "missing-context.jsonl", "conflicting-values.jsonl"):
            del contents[name]
            del report["files"][name]
        for scenario in ("missing-context", "conflicting-values"):
            del report["scenarios"][scenario]
        report["suiteReportVersion"] = 1
        contents["suite-report.json"] = json.dumps(report).encode()
        self.fixture.write(contents)
        self.assertEqual(1, verify_suite(self.fixture.archive)["suiteReportVersion"])
        with self.assertRaisesRegex(ValueError, "complete version-3"):
            prepare_release_assets(self.fixture.archive, self.output, "a" * 40)
        self.assertFalse(self.output.exists())

    def test_repeated_preparation_has_identical_checksums(self):
        second = self.output.parent / "release-assets-second"
        prepare_release_assets(self.fixture.archive, self.output, "a" * 40)
        prepare_release_assets(self.fixture.archive, second, "a" * 40)
        self.assertEqual((self.output / "SHA256SUMS").read_bytes(), (second / "SHA256SUMS").read_bytes())

    def test_missing_archive_does_not_leave_success_output(self):
        with self.assertRaises(OSError):
            prepare_release_assets(self.fixture.archive.parent / "missing.zip", self.output, "a" * 40)
        self.assertFalse(self.output.exists())

    def test_cli_failure_exits_nonzero_with_source_mismatch(self):
        stderr = io.StringIO()
        args = ["prepare_release_assets.py", str(self.fixture.archive), "--output", str(self.output),
                "--source-commit", "b" * 40]
        with patch("sys.argv", args), redirect_stderr(stderr), self.assertRaises(SystemExit) as result:
            main()
        self.assertEqual(1, result.exception.code)
        self.assertIn("expected release commit", stderr.getvalue())
