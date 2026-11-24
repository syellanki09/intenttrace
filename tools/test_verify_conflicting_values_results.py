import copy
import json
import tempfile
import unittest
from pathlib import Path

from verify_conflicting_values_results import verify_conflicting_values_results


class VerifyConflictingValuesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "conflicting-values.jsonl"
        metadata = dict(scenarioName="conflicting-values", scenarioVersion="1", sourceCommitSha="a" * 40,
                        seed=None, runtime="test runtime", jdk="17", operatingSystem="test OS",
                        configuration={"sourceDirty": "false"})
        primary = dict(source="primary", version=7, committedAt="2026-01-01T00:04:00Z", value="500")
        self.records = []
        for case, policy, cache, used, resolution, status in [
            ("control", "last-arrival", (7, "00:04", "500"), ("cache", "500"), None, "SATISFIED"),
            ("last-arrival-wins", "last-arrival", (8, "00:06", "750"), ("cache", "750"), None, "VIOLATED"),
            ("recorded-resolution", "prefer-primary-and-record", (8, "00:06", "750"), ("primary", "500"),
             dict(rule="prefer-primary", selectedSource="primary"), "SATISFIED"),
            ("same-version-divergence", "last-arrival", (7, "00:04", "750"), ("cache", "750"), None, "VIOLATED"),
        ]:
            observations = [dict(primary), dict(source="cache", version=cache[0],
                                                committedAt=f"2026-01-01T{cache[1]}:00Z", value=cache[2])]
            self.records.append(dict(schemaVersion=1, reproduction=copy.deepcopy(metadata), caseId=case,
                                     policy=policy, factKey="account-17/credit-limit",
                                     decidedAt="2026-01-01T00:08:00Z", observations=observations,
                                     usedSource=used[0], usedValue=used[1], resolution=resolution,
                                     failureClassId="conflicting-values", status=status, detected=None))

    def write(self):
        self.path.write_text("\n".join(json.dumps(row) for row in self.records) + "\n", encoding="utf-8")

    def test_valid_evidence(self):
        self.write()
        self.assertEqual(self.records, verify_conflicting_values_results(self.path))

    def test_forged_outcomes_fail(self):
        for index, field, value in [(1, "status", "SATISFIED"), (3, "status", "SATISFIED"), (2, "status", "VIOLATED"),
                                    (1, "detected", False), (1, "usedSource", "primary"), (1, "usedValue", "500"),
                                    (1, "resolution", dict(rule="prefer-primary", selectedSource="cache")),
                                    (2, "resolution", None), (0, "resolution",
                                                               dict(rule="prefer-primary", selectedSource="primary")),
                                    (0, "failureClassId", "missing-context"), (0, "schemaVersion", 1.0)]:
            original = self.records[index][field]
            self.records[index][field] = value
            self.write()
            with self.subTest(index=index, field=field, value=value), self.assertRaises(ValueError):
                verify_conflicting_values_results(self.path)
            self.records[index][field] = original

    def test_changed_observations_and_arrival_order_fail(self):
        for field, value in [("value", "500"), ("version", 7), ("version", 8.0), ("source", "replica"),
                             ("committedAt", "2026-01-01T00:07:00Z")]:
            row = self.records[1]["observations"][1]
            original = row[field]
            row[field] = value
            self.write()
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                verify_conflicting_values_results(self.path)
            row[field] = original
        self.records[1]["observations"].reverse()
        self.write()
        with self.assertRaises(ValueError):
            verify_conflicting_values_results(self.path)

    def test_mixed_runtime_and_missing_case_fail(self):
        self.records[3]["reproduction"]["jdk"] = "21"
        self.write()
        with self.assertRaises(ValueError):
            verify_conflicting_values_results(self.path)
        self.records[3]["reproduction"]["jdk"] = "17"
        self.records.pop()
        self.write()
        with self.assertRaises(ValueError):
            verify_conflicting_values_results(self.path)
