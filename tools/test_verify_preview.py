import hashlib
import json
import unittest
import warnings
import zipfile

import test_run_preview as fixtures
from verify_preview import verify_preview


class VerifyPreviewTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.PreviewTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.report = self.fixture.collect()
        self.path = self.fixture.destination / "preview.zip"
        with zipfile.ZipFile(self.path) as archive:
            self.contents = {name: archive.read(name) for name in archive.namelist()}

    def write(self, rehash=False):
        if rehash:
            for name, entry in self.report["files"].items():
                entry.update(sha256=hashlib.sha256(self.contents[name]).hexdigest(),
                             bytes=len(self.contents[name]))
        self.contents["report.json"] = json.dumps(self.report).encode()
        with zipfile.ZipFile(self.path, "w") as archive:
            for name, content in self.contents.items():
                archive.writestr(name, content)

    def test_valid_pack_with_or_without_build_log(self):
        self.assertEqual(self.report, verify_preview(self.path))
        self.contents["build.log"] = b"build output\n"
        self.report["files"]["build.log"] = {}
        self.write(rehash=True)
        self.assertEqual(self.report, verify_preview(self.path))

    def test_corrupted_evidence_is_rejected(self):
        self.contents["contract-unit-drift.jsonl"] += b" "
        self.write()
        with self.assertRaisesRegex(ValueError, "Checksum"):
            verify_preview(self.path)

    def test_rehashed_false_ground_truth_is_rejected(self):
        rows = [json.loads(line) for line in self.contents["contract-unit-drift.jsonl"].splitlines()]
        rows[1]["status"] = "SATISFIED"
        self.contents["contract-unit-drift.jsonl"] = b"\n".join(json.dumps(row).encode() for row in rows)
        self.write(rehash=True)
        with self.assertRaises(ValueError):
            verify_preview(self.path)

    def test_forged_summary_source_runtime_and_version_are_rejected(self):
        for key, value in [("sourceCommitSha", "b" * 40), ("runtime", {}),
                           ("previewReportVersion", True), ("staleContext", [])]:
            original = self.report[key]
            self.report[key] = value
            self.write()
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_preview(self.path)
            self.report[key] = original

    def test_duplicate_and_unexpected_members_are_rejected(self):
        for name in ("report.json", "../escape"):
            self.write()
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(self.path, "a") as archive:
                    archive.writestr(name, b"{}")
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_preview(self.path)

    def test_missing_checksum_coverage_is_rejected(self):
        del self.report["files"]["contract-unit-drift.jsonl"]
        self.write()
        with self.assertRaisesRegex(ValueError, "cover all"):
            verify_preview(self.path)

    def test_nested_bundle_must_contain_the_exact_outer_records(self):
        self.contents["stale-context.jsonl"] = b" " + self.contents["stale-context.jsonl"]
        self.write(rehash=True)
        with self.assertRaisesRegex(ValueError, "Nested bundle disagrees"):
            verify_preview(self.path)

    def test_duplicate_report_fields_are_rejected(self):
        self.contents["report.json"] = self.contents["report.json"].replace(
            b'"previewReportVersion": 1', b'"previewReportVersion": 1, "previewReportVersion": 1')
        with zipfile.ZipFile(self.path, "w") as archive:
            for name, content in self.contents.items():
                archive.writestr(name, content)
        with self.assertRaises(ValueError):
            verify_preview(self.path)
