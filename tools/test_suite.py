import hashlib
import io
import json
import subprocess
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from contextlib import redirect_stderr, redirect_stdout
from unittest.mock import patch

import test_run_preview as preview_fixtures
import test_verify_event_time_results as event_fixtures
import test_verify_replay_results as replay_fixtures
from run_suite import collect_suite, run_suite
from verify_suite import verify_suite


class SuiteTest(unittest.TestCase):
    def setUp(self):
        self.preview = preview_fixtures.PreviewTest()
        self.preview.setUp()
        self.addCleanup(self.preview.doCleanups)
        self.preview.collect()
        self.replay = replay_fixtures.VerifyReplayTest()
        self.replay.setUp()
        self.addCleanup(self.replay.doCleanups)
        self.events = event_fixtures.VerifyEventTimeTest()
        self.events.setUp()
        self.addCleanup(self.events.doCleanups)
        for record in self.replay.records + self.events.records:
            for field in ("runtime", "jdk", "operatingSystem"):
                record["reproduction"][field] = self.preview.stale.records[0]["reproduction"][field]
        self.destination = Path(self.preview.stale.directory.name) / "suite"
        self.archive = self.destination / "suite.zip"

    def collect(self):
        self.replay.write()
        self.events.write()
        return collect_suite(self.preview.stale.source, self.preview.contract_path,
                             self.replay.path, self.events.path, self.destination, "a" * 40)

    def contents(self):
        with zipfile.ZipFile(self.archive) as archive:
            return {name: archive.read(name) for name in archive.namelist()}

    def write(self, contents, rehash=False):
        if rehash:
            report = json.loads(contents["suite-report.json"])
            for name, entry in report["files"].items():
                entry.update(sha256=hashlib.sha256(contents[name]).hexdigest(), bytes=len(contents[name]))
            contents["suite-report.json"] = json.dumps(report).encode()
        with zipfile.ZipFile(self.archive, "w") as archive:
            for name, value in contents.items():
                archive.writestr(name, value)

    def test_all_twelve_cases_and_existing_preview_are_preserved(self):
        report = self.collect()
        self.assertEqual(report, verify_suite(self.archive))
        self.assertEqual([2, 3, 3, 4], [len(report["scenarios"][key]) for key in
            ("stale-context", "contract-unit-drift", "duplicate-replay", "event-time-ordering")])
        self.assertTrue(all(row["detected"] is None for cases in report["scenarios"].values() for row in cases))
        self.assertEqual((self.destination / "preview/preview.zip").read_bytes(), self.contents()["preview.zip"])

    def test_logs_are_optional_and_hashed_when_present(self):
        self.destination.mkdir()
        (self.destination / "build.log").write_text("actual build output\n", encoding="utf-8")
        report = self.collect()
        self.assertIn("build.log", report["files"])
        self.assertEqual(report, verify_suite(self.archive))

    def test_mixed_commit_runtime_and_dirty_evidence_cannot_form_suite(self):
        for field, value in [("sourceCommitSha", "b" * 40), ("jdk", "21"),
                             ("configuration", {"sourceDirty": "true"})]:
            originals = [row["reproduction"][field] for row in self.events.records]
            for row in self.events.records:
                row["reproduction"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.collect()
            self.assertFalse(self.archive.exists())
            for row, original in zip(self.events.records, originals):
                row["reproduction"][field] = original

    def test_corrupted_and_rehashed_forged_evidence_are_rejected(self):
        self.collect()
        contents = self.contents()
        original = contents["duplicate-replay.jsonl"]
        contents["duplicate-replay.jsonl"] += b" "
        self.write(contents)
        with self.assertRaisesRegex(ValueError, "Checksum"):
            verify_suite(self.archive)
        rows = [json.loads(line) for line in original.splitlines()]
        rows[1]["status"] = "SATISFIED"
        contents["duplicate-replay.jsonl"] = b"\n".join(json.dumps(row).encode() for row in rows)
        self.write(contents, rehash=True)
        with self.assertRaises(ValueError):
            verify_suite(self.archive)

    def test_forged_suite_summary_and_version_are_rejected(self):
        self.collect()
        contents = self.contents()
        original = contents["suite-report.json"]
        for key, value in [("sourceCommitSha", "b" * 40), ("suiteReportVersion", True), ("scenarios", {})]:
            report = json.loads(original)
            report[key] = value
            contents["suite-report.json"] = json.dumps(report).encode()
            self.write(contents)
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_suite(self.archive)

    def test_duplicate_or_unexpected_archive_members_are_rejected(self):
        self.collect()
        contents = self.contents()
        for name in ("preview.zip", "../escape"):
            self.write(contents)
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(self.archive, "a") as archive:
                    archive.writestr(name, b"bad")
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_suite(self.archive)

    def test_report_must_cover_all_evidence(self):
        self.collect()
        contents = self.contents()
        report = json.loads(contents["suite-report.json"])
        del report["files"]["event-time-ordering.jsonl"]
        contents["suite-report.json"] = json.dumps(report).encode()
        self.write(contents)
        with self.assertRaisesRegex(ValueError, "cover every"):
            verify_suite(self.archive)


class SuiteRunnerTest(unittest.TestCase):
    def test_failed_build_preserves_diagnostics_without_successful_bundle(self):
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            with patch("run_suite.subprocess.check_output", side_effect=["a" * 40, ""]), \
                 patch("run_suite.subprocess.run", side_effect=subprocess.CalledProcessError(1, "gradle")):
                with self.assertRaises(subprocess.CalledProcessError):
                    run_suite(directory)
            root = Path(directory) / "build/suite"
            self.assertEqual(1, len(list(root.glob("*/build.log"))))
            self.assertEqual([], list(root.glob("*/suite.zip")))
            self.assertEqual([], list(root.glob("*/suite-report.json")))

    def test_source_changes_abort_before_evidence_collection(self):
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            with patch("run_suite.subprocess.check_output", side_effect=["a" * 40, "", "b" * 40]), \
                 patch("run_suite.subprocess.run"), patch("run_suite.collect_suite") as collect:
                with self.assertRaisesRegex(ValueError, "Source changed"):
                    run_suite(directory)
                collect.assert_not_called()

    def test_dirty_checkout_cannot_start_build(self):
        with tempfile.TemporaryDirectory() as directory, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            with patch("run_suite.subprocess.check_output", side_effect=["a" * 40, " M source"]), \
                 patch("run_suite.subprocess.run") as build:
                with self.assertRaisesRegex(ValueError, "clean Git"):
                    run_suite(directory)
                build.assert_not_called()
