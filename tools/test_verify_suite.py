import copy
import hashlib
import json
import unittest
from unittest.mock import patch, Mock

import test_suite as fixtures
from verify_suite import verify_suite


class IndexedSuiteTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.SuiteTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.collect()
        self.contents = self.fixture.contents()
        self.manifest = json.loads(self.contents["manifest.json"])

    def test_manifest_indexes_all_raw_evidence_in_fixed_order(self):
        self.assertEqual(1, self.manifest["schemaVersion"])
        self.assertEqual("a" * 40, self.manifest["sourceCommit"])
        self.assertIs(self.manifest["sourceDirty"], False)
        self.assertEqual(["stale-context", "contract-unit-drift", "duplicate-replay", "event-time-ordering"],
                         [row["id"] for row in self.manifest["scenarios"]])
        self.assertEqual([2, 3, 3, 4], [row["recordCount"] for row in self.manifest["scenarios"]])
        for row in self.manifest["scenarios"]:
            raw = self.contents[row["resultPath"]]
            self.assertEqual(row["sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(row["recordCount"], len(raw.splitlines()))
            self.assertIs(row["verified"], True)
        self.assertEqual(["delayed-events", "stale-context"], self.manifest["scenarios"][-1]["failureClassIds"])

    def test_wrong_counts_hashes_flags_identity_and_failure_classes_are_rejected(self):
        for field, value in [("recordCount", 3), ("recordCount", True), ("sha256", "0" * 64),
                             ("verified", False), ("id", "different"), ("failureClassIds", ["delayed-events"])]:
            manifest = copy.deepcopy(self.manifest)
            manifest["scenarios"][0][field] = value
            self.contents["manifest.json"] = json.dumps(manifest).encode()
            self.fixture.write(self.contents, rehash=True)
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                verify_suite(self.fixture.archive)

    def test_missing_duplicate_and_reordered_scenarios_are_rejected(self):
        scenarios = self.manifest["scenarios"]
        for rows in (scenarios[:-1], scenarios + [scenarios[0]], list(reversed(scenarios))):
            manifest = dict(self.manifest, scenarios=rows)
            self.contents["manifest.json"] = json.dumps(manifest).encode()
            self.fixture.write(self.contents, rehash=True)
            with self.assertRaises(ValueError):
                verify_suite(self.fixture.archive)

    def test_malformed_unsupported_and_ambiguous_manifests_are_rejected(self):
        valid = json.dumps(self.manifest).encode()
        for raw in (b"{", valid.replace(b'"schemaVersion": 1', b'"schemaVersion": 2'),
                    valid.replace(b'"schemaVersion": 1', b'"schemaVersion": true'),
                    valid.replace(b'"schemaVersion": 1', b'"schemaVersion": 1, "schemaVersion": 1')):
            self.contents["manifest.json"] = raw
            self.fixture.write(self.contents, rehash=True)
            with self.assertRaises(ValueError):
                verify_suite(self.fixture.archive)

    def test_missing_raw_file_is_rejected(self):
        del self.contents["stale-context.jsonl"]
        self.fixture.write(self.contents)
        with self.assertRaises(ValueError):
            verify_suite(self.fixture.archive)

    def test_raw_evidence_must_match_nested_preview_even_after_rehashing(self):
        self.contents["stale-context.jsonl"] = b" " + self.contents["stale-context.jsonl"]
        self.manifest["scenarios"][0]["sha256"] = hashlib.sha256(self.contents["stale-context.jsonl"]).hexdigest()
        self.contents["manifest.json"] = json.dumps(self.manifest).encode()
        self.fixture.write(self.contents, rehash=True)
        with self.assertRaisesRegex(ValueError, "Nested preview disagrees"):
            verify_suite(self.fixture.archive)

    def test_version_one_archive_still_verifies(self):
        for name in ("manifest.json", "stale-context.jsonl", "contract-unit-drift.jsonl"):
            del self.contents[name]
        report = json.loads(self.contents["suite-report.json"])
        report["suiteReportVersion"] = 1
        for name in ("manifest.json", "stale-context.jsonl", "contract-unit-drift.jsonl"):
            del report["files"][name]
        self.contents["suite-report.json"] = json.dumps(report).encode()
        self.fixture.write(self.contents)
        self.assertEqual(report, verify_suite(self.fixture.archive))

    def test_archive_size_is_checked_before_member_reads(self):
        with patch("verify_suite.zipfile.ZipFile.infolist", return_value=[Mock(file_size=17 * 1024 * 1024)]), \
             patch("verify_suite.zipfile.ZipFile.read", side_effect=AssertionError("Unexpected read")):
            with self.assertRaisesRegex(ValueError, "16 MiB"):
                verify_suite(self.fixture.archive)
