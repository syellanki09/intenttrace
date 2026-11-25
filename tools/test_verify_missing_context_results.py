import copy
import json
import tempfile
import unittest
from pathlib import Path

from verify_missing_context_results import verify_missing_context_results


class VerifyMissingContextTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "missing-context.jsonl"
        metadata = dict(scenarioName="missing-context", scenarioVersion="1", sourceCommitSha="a" * 40,
                        seed=None, runtime="test runtime", jdk="17", operatingSystem="test OS",
                        configuration={"sourceDirty": "false"})
        required = dict(version=4, committedAt="2026-01-01T00:05:00Z", value="gold")
        self.records = []
        for case, policy, delivered, used, degradation, status in [
            ("control", "default-silently", True, "gold", None, "SATISFIED"),
            ("silent-default", "default-silently", False, "standard", None, "VIOLATED"),
            ("recorded-degradation", "default-and-record", False, "standard",
             dict(reason="context-source-empty", source="tier-service"), "SATISFIED"),
        ]:
            self.records.append(dict(schemaVersion=1, reproduction=copy.deepcopy(metadata), caseId=case,
                                     policy=policy, contextKey="member-42/discount-tier",
                                     decidedAt="2026-01-01T00:08:00Z", required=dict(required),
                                     delivered=dict(required) if delivered else None, defaultValue="standard",
                                     usedValue=used, degradation=degradation, failureClassId="missing-context",
                                     status=status, detected=None))

    def write(self):
        self.path.write_text("\n".join(json.dumps(row) for row in self.records) + "\n", encoding="utf-8")

    def test_valid_evidence(self):
        self.write()
        self.assertEqual(self.records, verify_missing_context_results(self.path))

    def test_forged_outcomes_fail(self):
        for index, field, value in [(1, "status", "SATISFIED"), (2, "status", "VIOLATED"), (1, "detected", True),
                                    (1, "usedValue", "gold"), (1, "degradation", dict(reason="x", source="y")),
                                    (2, "degradation", None), (0, "failureClassId", "stale-context"),
                                    (0, "schemaVersion", True), (1, "policy", "default-and-record")]:
            original = self.records[index][field]
            self.records[index][field] = value
            self.write()
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                verify_missing_context_results(self.path)
            self.records[index][field] = original

    def test_changed_read_evidence_fails(self):
        for index, key, field, value in [(0, "delivered", "version", 3), (0, "delivered", "version", 4.0),
                                         (0, "required", "value", "silver"),
                                         (0, "required", "committedAt", "2026-01-01T00:09:00Z")]:
            original = self.records[index][key][field]
            self.records[index][key][field] = value
            self.write()
            with self.subTest(key=key, field=field, value=value), self.assertRaises(ValueError):
                verify_missing_context_results(self.path)
            self.records[index][key][field] = original
        self.records[1]["delivered"] = dict(self.records[1]["required"])
        self.write()
        with self.assertRaises(ValueError):
            verify_missing_context_results(self.path)

    def test_mixed_source_and_missing_case_fail(self):
        self.records[2]["reproduction"]["sourceCommitSha"] = "b" * 40
        self.write()
        with self.assertRaises(ValueError):
            verify_missing_context_results(self.path)
        self.records[2]["reproduction"]["sourceCommitSha"] = "a" * 40
        self.records.pop()
        self.write()
        with self.assertRaises(ValueError):
            verify_missing_context_results(self.path)
