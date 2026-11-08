import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import test_bundle_results as stale_fixtures
import test_verify_contract_results as contract_fixtures
from run_preview import collect_preview


class PreviewTest(unittest.TestCase):
    def setUp(self):
        self.stale = stale_fixtures.BundleResultsTest()
        self.stale.setUp()
        self.addCleanup(self.stale.doCleanups)
        self.stale.configure_versioned()
        self.stale.write()
        fixture = contract_fixtures.VerifyContractResultsTest()
        fixture.setUp()
        self.contract = fixture.records
        for record in self.contract:
            for field in ("runtime", "jdk", "operatingSystem"):
                record["reproduction"][field] = self.stale.records[0]["reproduction"][field]
        self.contract_path = Path(self.stale.directory.name) / "contract.jsonl"
        self.destination = Path(self.stale.directory.name) / "preview"

    def collect(self):
        self.contract_path.write_text("\n".join(json.dumps(record) for record in self.contract) + "\n", encoding="utf-8")
        return collect_preview(self.stale.source, self.contract_path, self.destination, "a" * 40)

    def test_review_pack_contains_verified_evidence_and_accurate_summary(self):
        report = self.collect()
        self.assertEqual(["SATISFIED", "VIOLATED"], [row["status"] for row in report["staleContext"]])
        self.assertEqual(["SATISFIED", "VIOLATED", "VIOLATED"], [row["status"] for row in report["contractUnitDrift"]])
        with zipfile.ZipFile(self.destination / "preview.zip") as archive:
            self.assertEqual(report, json.loads(archive.read("report.json")))
            for name, entry in report["files"].items():
                self.assertEqual(entry["sha256"], hashlib.sha256(archive.read(name)).hexdigest())
            self.assertTrue(all(row["detected"] is None for row in report["contractUnitDrift"]))

    def test_mixed_source_and_runtime_cannot_form_a_preview(self):
        for field, value in [("sourceCommitSha", "b" * 40), ("jdk", "21")]:
            originals = [record["reproduction"][field] for record in self.contract]
            for record in self.contract:
                record["reproduction"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.collect()
            for record, original in zip(self.contract, originals):
                record["reproduction"][field] = original
        self.assertFalse((self.destination / "preview.zip").exists())

    def test_dirty_or_corrupted_contract_evidence_cannot_be_labeled_verified(self):
        for record in self.contract:
            record["reproduction"]["configuration"]["sourceDirty"] = "true"
        with self.assertRaises(ValueError):
            self.collect()
        self.assertFalse((self.destination / "report.json").exists())
        self.contract[1]["status"] = "SATISFIED"
        with self.assertRaises(ValueError):
            self.collect()

    def test_exact_raw_files_are_preserved(self):
        self.collect()
        self.assertEqual(self.stale.source.read_bytes(), (self.destination / "stale-context.jsonl").read_bytes())
        self.assertEqual(self.contract_path.read_bytes(), (self.destination / "contract-unit-drift.jsonl").read_bytes())
