import copy
import json
import tempfile
import unittest
from pathlib import Path

from verify_event_time_results import verify_event_time_results


class VerifyEventTimeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "event-time.jsonl"
        metadata = dict(scenarioName="event-time-ordering", scenarioVersion="1", sourceCommitSha="a" * 40,
                        seed=None, runtime="test runtime", jdk="17", operatingSystem="test OS",
                        configuration={"sourceDirty": "false"})
        self.records = []
        for case, policy, a_ingest, b_ingest, observed, status, failure in [
            ("control", "arrival-order", 12, 22, "b", "SATISFIED", "stale-context"),
            ("reordered-arrival", "arrival-order", 32, 22, "a", "VIOLATED", "stale-context"),
            ("event-time-reconciliation", "event-time", 32, 22, "b", "SATISFIED", "stale-context"),
            ("late-arrival", "event-time", 12, 45, "a", "VIOLATED", "delayed-events"),
        ]:
            def stamp(second):
                return f"2026-01-01T00:00:{second:02d}Z"
            deliveries = [dict(eventId="interest-a", occurredAt=stamp(10), ingestedAt=stamp(a_ingest), value="travel"),
                          dict(eventId="interest-b", occurredAt=stamp(20), ingestedAt=stamp(b_ingest), value="technology")]
            self.records.append(dict(schemaVersion=1, reproduction=copy.deepcopy(metadata), caseId=case, policy=policy,
                decidedAt=stamp(40), deliveries=sorted(deliveries, key=lambda row: row["ingestedAt"]),
                expectedEventId="interest-b", expectedValue="technology", observedEventId=f"interest-{observed}",
                observedValue="travel" if observed == "a" else "technology", status=status,
                failureClassId=failure, detected=None))

    def write(self):
        self.path.write_text("\n".join(json.dumps(row) for row in self.records) + "\n", encoding="utf-8")

    def test_valid_ordering_and_late_arrival_evidence(self):
        self.write()
        self.assertEqual(self.records, verify_event_time_results(self.path))

    def test_forged_observation_status_and_taxonomy_fail(self):
        for key, value in [("observedValue", "technology"), ("observedEventId", "interest-b"),
                           ("expectedValue", "travel"), ("status", "SATISFIED"), ("detected", True),
                           ("failureClassId", "duplicate-replay"), ("schemaVersion", True)]:
            original = self.records[1][key]
            self.records[1][key] = value
            self.write()
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_event_time_results(self.path)
            self.records[1][key] = original

    def test_changed_clock_history_cannot_claim_the_fixed_experiment(self):
        row = self.records[3]["deliveries"][1]
        for key, value in [("ingestedAt", "2026-01-01T00:00:39Z"),
                           ("occurredAt", "2026-01-01T00:00:50Z"), ("ingestedAt", "bad timestamp")]:
            original = row[key]
            row[key] = value
            self.write()
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_event_time_results(self.path)
            row[key] = original

    def test_source_disagreement_and_missing_cases_fail(self):
        self.records[3]["reproduction"]["sourceCommitSha"] = "b" * 40
        self.write()
        with self.assertRaises(ValueError):
            verify_event_time_results(self.path)
        self.records.pop()
        self.write()
        with self.assertRaises(ValueError):
            verify_event_time_results(self.path)
