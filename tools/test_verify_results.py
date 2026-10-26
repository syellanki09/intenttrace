import copy
import json
import tempfile
import unittest
from pathlib import Path

from verify_results import verify


class VerifyResultsTest(unittest.TestCase):
    def setUp(self):
        self.records = []
        for execution, version, outcome, status in [
            ("CONTROL", 2, "technology", "SATISFIED"),
            ("INJECTED", 1, "travel", "VIOLATED"),
        ]:
            self.records.append({
                "schemaVersion": 1, "execution": execution, "contextId": "user-001",
                "decidedAt": "2026-01-01T00:00:03Z", "outcome": outcome,
                "degradationReason": None,
                "reproduction": {"scenarioName": "stale-context", "scenarioVersion": "1"},
                "usedContext": {"id": "user-001", "version": version,
                                "attributes": {"preferredCategory": outcome}},
                "latestEligibleContext": {"id": "user-001", "version": 2},
                "evaluation": {"failureClassId": "stale-context", "status": status,
                               "observedVersion": version, "expectedVersion": 2, "detected": None},
            })

    def check(self, records):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "results.jsonl"
            path.write_text("\n".join(json.dumps(record) for record in records) + "\n", encoding="utf-8")
            return verify(path)

    def test_valid_pair(self):
        self.assertEqual(self.records, self.check(self.records))

    def test_corrupted_evidence_is_rejected(self):
        for field, value in [("status", "SATISFIED"), ("detected", True),
                             ("observedVersion", 2), ("failureClassId", "typo")]:
            with self.subTest(field=field):
                records = copy.deepcopy(self.records)
                records[1]["evaluation"][field] = value
                with self.assertRaises(ValueError):
                    self.check(records)

    def test_missing_duplicate_or_reversed_executions_are_rejected(self):
        for records in [self.records[:1], self.records * 2, list(reversed(self.records))]:
            with self.assertRaises(ValueError):
                self.check(records)

    def test_mismatched_pair_metadata_is_rejected(self):
        records = copy.deepcopy(self.records)
        records[1]["decidedAt"] = "2026-01-01T00:00:04Z"
        with self.assertRaises(ValueError):
            self.check(records)


if __name__ == "__main__":
    unittest.main()
