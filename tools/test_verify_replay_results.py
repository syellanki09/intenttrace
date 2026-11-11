import copy
import json
import tempfile
import unittest
from pathlib import Path

from verify_replay_results import verify_replay_results


class VerifyReplayTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "replay.jsonl"
        metadata = dict(scenarioName="duplicate-replay", scenarioVersion="1", sourceCommitSha="a" * 40,
                        seed=None, runtime="test runtime", jdk="17", operatingSystem="test OS",
                        configuration={"sourceDirty": "false"})
        self.records = []
        for case, policy, replayed, total, status in [
            ("control", "apply-every-delivery", False, 8, "SATISFIED"),
            ("naive-replay", "apply-every-delivery", True, 11, "VIOLATED"),
            ("idempotent-replay", "deduplicate-by-event-id", True, 8, "SATISFIED"),
        ]:
            deliveries = [dict(eventId="interest-a", points=3, applied=True),
                          dict(eventId="interest-b", points=5, applied=True)]
            if replayed:
                deliveries.append(dict(eventId="interest-a", points=3, applied=case != "idempotent-replay"))
            self.records.append(dict(schemaVersion=1, reproduction=copy.deepcopy(metadata), caseId=case,
                                     policy=policy, deliveries=deliveries, expectedTotal=8, observedTotal=total,
                                     status=status, failureClassId="duplicate-replay", detected=None))

    def write(self):
        self.path.write_text("\n".join(json.dumps(row) for row in self.records) + "\n", encoding="utf-8")

    def test_valid_delivery_evidence(self):
        self.write()
        self.assertEqual(self.records, verify_replay_results(self.path))

    def test_forged_totals_status_detection_and_types_fail(self):
        for field, value in [("observedTotal", 8), ("expectedTotal", 11), ("status", "SATISFIED"),
                             ("detected", True), ("schemaVersion", True), ("failureClassId", "stale-context")]:
            original = self.records[1][field]
            self.records[1][field] = value
            self.write()
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify_replay_results(self.path)
            self.records[1][field] = original

    def test_changed_payload_or_application_markers_fail(self):
        row = self.records[2]["deliveries"][2]
        for field, value in [("eventId", "other"), ("points", 4), ("points", 3.0), ("applied", True), ("applied", 0)]:
            original = row[field]
            row[field] = value
            self.write()
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                verify_replay_results(self.path)
            row[field] = original

    def test_mixed_source_and_missing_case_fail(self):
        self.records[2]["reproduction"]["sourceCommitSha"] = "b" * 40
        self.write()
        with self.assertRaises(ValueError):
            verify_replay_results(self.path)
        self.records.pop()
        self.write()
        with self.assertRaises(ValueError):
            verify_replay_results(self.path)
