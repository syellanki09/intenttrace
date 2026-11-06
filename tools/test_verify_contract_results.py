import copy
import json
import tempfile
import unittest
from pathlib import Path

from verify_contract_results import verify_contract_results


class VerifyContractResultsTest(unittest.TestCase):
    def setUp(self):
        self.records = []
        metadata = {"scenarioName": "contract-unit-drift", "scenarioVersion": "1",
                    "sourceCommitSha": "a" * 40, "runtime": "runtime", "jdk": "17",
                    "operatingSystem": "os", "seed": None,
                    "configuration": {"logicalDurationMillis": "2000", "sourceDirty": "false"}}
        for case, unit, version, value in [("compatible-upgrade", "SECONDS", 2, 2),
            ("unit-drift", "MILLISECONDS", 2, 2000), ("same-version-drift", "MILLISECONDS", 1, 2000)]:
            self.records.append({"schemaVersion": 1, "caseId": case, "reproduction": copy.deepcopy(metadata),
                "logicalDurationMillis": 2000, "producerUnit": unit, "consumerUnit": "SECONDS",
                "referenceWirePayload": json.dumps({"inputId": "session-duration-001", "contractVersion": 1, "duration": 2000}),
                "wirePayload": json.dumps({"inputId": "session-duration-001", "contractVersion": version, "duration": value}),
                "reference": {"inputId": "session-duration-001", "clientId": "web", "contractVersion": 1, "outcome": "durationMillis:2000"},
                "observed": {"inputId": "session-duration-001", "clientId": "mobile", "contractVersion": version, "outcome": f"durationMillis:{value * 1000}"},
                "failureClassId": "contract-incompatibility", "status": "SATISFIED" if value == 2 else "VIOLATED", "detected": None})

    def check(self, records):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "results.jsonl"
            path.write_text("\n".join(json.dumps(record) for record in records) + "\n", encoding="utf-8")
            return verify_contract_results(path)

    def test_valid_evidence(self):
        self.assertEqual(self.records, self.check(self.records))

    def test_outcome_status_and_detection_corruption_are_rejected(self):
        for field, value in [("status", "SATISFIED"), ("detected", True), ("producerUnit", "SECONDS")]:
            records = copy.deepcopy(self.records)
            records[1][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.check(records)
        records = copy.deepcopy(self.records)
        records[1]["observed"]["outcome"] = "durationMillis:2000"
        with self.assertRaises(ValueError):
            self.check(records)

    def test_wire_corruption_is_recomputed_not_trusted(self):
        for value in (2001, True):
            records = copy.deepcopy(self.records)
            wire = json.loads(records[1]["wirePayload"])
            wire["duration"] = value
            records[1]["wirePayload"] = json.dumps(wire)
            with self.assertRaises(ValueError):
                self.check(records)

    def test_missing_reordered_and_mixed_source_cases_are_rejected(self):
        for records in [self.records[:2], list(reversed(self.records))]:
            with self.assertRaises(ValueError):
                self.check(records)
        records = copy.deepcopy(self.records)
        records[1]["reproduction"]["sourceCommitSha"] = "b" * 40
        with self.assertRaises(ValueError):
            self.check(records)
