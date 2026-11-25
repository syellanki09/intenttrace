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
        self.assertEqual(2, self.manifest["schemaVersion"])
        self.assertEqual("a" * 40, self.manifest["sourceCommit"])
        self.assertIs(self.manifest["sourceDirty"], False)
        self.assertEqual(["stale-context", "contract-unit-drift", "duplicate-replay", "event-time-ordering",
                          "missing-context", "conflicting-values"],
                         [row["id"] for row in self.manifest["scenarios"]])
        self.assertEqual([2, 3, 3, 4, 3, 4], [row["recordCount"] for row in self.manifest["scenarios"]])
        for row in self.manifest["scenarios"]:
            raw = self.contents[row["resultPath"]]
            self.assertEqual(row["sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(row["recordCount"], len(raw.splitlines()))
            self.assertIs(row["verified"], True)
        self.assertEqual(["delayed-events", "stale-context"], self.manifest["scenarios"][3]["failureClassIds"])
        self.assertEqual([["missing-context"], ["conflicting-values"]],
                         [row["failureClassIds"] for row in self.manifest["scenarios"][4:]])

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
        for raw in (b"{", valid.replace(b'"schemaVersion": 2', b'"schemaVersion": 1'),
                    valid.replace(b'"schemaVersion": 2', b'"schemaVersion": 3'),
                    valid.replace(b'"schemaVersion": 2', b'"schemaVersion": true'),
                    valid.replace(b'"schemaVersion": 2', b'"schemaVersion": 2, "schemaVersion": 2')):
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

    def legacy(self, version):
        """Rewrite the fixture as the four-scenario suite that earlier tooling produced."""
        removed = ["missing-context.jsonl", "conflicting-values.jsonl"]
        if version == 1:
            removed += ["manifest.json", "stale-context.jsonl", "contract-unit-drift.jsonl"]
        report = json.loads(self.contents["suite-report.json"])
        report["suiteReportVersion"] = version
        for name in removed:
            del self.contents[name]
            del report["files"][name]
        for scenario in ("missing-context", "conflicting-values"):
            del report["scenarios"][scenario]
        if version == 2:
            manifest = dict(self.manifest, schemaVersion=1, scenarios=self.manifest["scenarios"][:4])
            self.contents["manifest.json"] = json.dumps(manifest).encode()
            report["files"]["manifest.json"].update(sha256=hashlib.sha256(self.contents["manifest.json"]).hexdigest(),
                                                    bytes=len(self.contents["manifest.json"]))
        self.contents["suite-report.json"] = json.dumps(report).encode()
        self.fixture.write(self.contents)
        return report

    def test_version_one_archive_still_verifies(self):
        report = self.legacy(1)
        self.assertEqual(report, verify_suite(self.fixture.archive))

    def test_version_two_archive_still_verifies(self):
        report = self.legacy(2)
        self.assertEqual(report, verify_suite(self.fixture.archive))

    def test_partial_new_evidence_is_rejected(self):
        for name in ("missing-context.jsonl", "conflicting-values.jsonl"):
            contents = dict(self.contents)
            del contents[name]
            self.fixture.write(contents)
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_suite(self.fixture.archive)

    def test_version_two_manifest_cannot_stand_in_a_complete_suite(self):
        manifest = dict(self.manifest, schemaVersion=1, scenarios=self.manifest["scenarios"][:4])
        self.contents["manifest.json"] = json.dumps(manifest).encode()
        self.fixture.write(self.contents, rehash=True)
        with self.assertRaises(ValueError):
            verify_suite(self.fixture.archive)

    def test_archive_size_is_checked_before_member_reads(self):
        with patch("verify_suite.zipfile.ZipFile.infolist", return_value=[Mock(file_size=17 * 1024 * 1024)]), \
             patch("verify_suite.zipfile.ZipFile.read", side_effect=AssertionError("Unexpected read")):
            with self.assertRaisesRegex(ValueError, "16 MiB"):
                verify_suite(self.fixture.archive)
