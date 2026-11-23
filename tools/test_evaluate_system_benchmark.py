import contextlib
import io
import json
import os
import tempfile
import unittest

import evaluate_system_benchmark as evaluator
import run_system_benchmark as runner


def write_jsonl(path, records):
    with open(path, "w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record) + "\n")


def commit(event_id, user, version, micros):
    return {"eventId": event_id, "user": user, "version": version, "committedMicros": micros,
            "payload": f"p{user}.{version}", "fault": "none"}


def decision(decision_id, user, decided, version, occurred, phase="fixed", nanos=0):
    return {"decisionId": decision_id, "user": user, "decidedMicros": decided, "decidedNanos": decided * 1000 + nanos,
            "version": version, "occurredMicros": occurred, "phase": phase}


def arrival(event_id, user, version, occurred, ingested, payload, nanos=0):
    return {"eventId": event_id, "user": user, "version": version, "occurredMicros": occurred,
            "ingestedMicros": ingested, "ingestedNanos": ingested * 1000 + nanos, "payload": payload}


def applied(event_id, user, version, occurred, at):
    return {"eventId": event_id, "user": user, "version": version, "occurredMicros": occurred, "appliedMicros": at}


def finding(cls, decision_id=None, event_id=None, confidence="CONFIRMED"):
    record = {"schemaVersion": 1, "failureClassId": cls, "confidence": confidence, "contextKey": "u1"}
    if decision_id:
        record["decisionId"] = decision_id
    if event_id:
        record["eventId"] = event_id
    return record


class FixtureRun:
    """A tiny run whose ground truth is worked out by hand in the test below."""

    def __init__(self, directory, findings, lag=0):
        self.directory = directory
        with open(os.path.join(directory, "config.json"), "w", encoding="utf-8") as handle:
            json.dump({"runId": "fixture", "mode": "on", "maxVersionLag": lag}, handle)
        write_jsonl(os.path.join(directory, "commits-preload.jsonl"),
                    [commit("preload-1", 1, 1, 1000), commit("preload-2", 2, 1, 1000)])
        write_jsonl(os.path.join(directory, "commits.jsonl"),
                    [commit("e1", 1, 2, 2000), commit("e2", 1, 3, 3000)])
        write_jsonl(os.path.join(directory, "decisions-0.jsonl"), [
            decision("d1", 1, 2500, 1, 1000),                    # stale: version 2 committed at 2000
            decision("d2", 1, 3500, 3, 3000, "saturation"),      # fresh
        ])
        write_jsonl(os.path.join(directory, "decisions-1.jsonl"), [
            decision("d3", 2, 2600, None, None),                 # missing: version 1 was committed
            decision("d4", 1, 2100, 2, 2000),                    # fresh, reflects e1
        ])
        write_jsonl(os.path.join(directory, "arrivals.jsonl"), [
            arrival("e1", 1, 2, 2000, 2600, "pa"),               # arrives after d1, which needed it
            arrival("e2", 1, 3, 3000, 3100, "pb"),
            arrival("e1", 1, 2, 2000, 3200, "pa"),               # duplicate
            arrival("e2", 1, 3, 3000, 3300, "pb.conflict"),      # conflicting
        ])
        write_jsonl(os.path.join(directory, "applies.jsonl"), [
            applied("e1", 1, 2, 2000, 2700),
            applied("e2", 1, 3, 3000, 3150),
            applied("e1", 1, 2, 2000, 3250),                     # older event replaced newer state
        ])
        write_jsonl(os.path.join(directory, "findings.jsonl"), findings)


class EvaluateSystemBenchmarkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)

    def evaluate(self, findings, lag=0):
        FixtureRun(self.temp.name, findings, lag)
        return evaluator.evaluate(self.temp.name)

    def test_ground_truth_and_scoring_for_every_category(self):
        result = self.evaluate([
            finding("stale-context", decision_id="d1"),
            finding("stale-context", decision_id="d2"),          # false positive
            finding("duplicate-replay", event_id="e1"),
            finding("conflicting-values", event_id="e2"),
            finding("stale-context", event_id="e1"),
            finding("delayed-events", decision_id="d1", event_id="e1"),
        ])
        categories = result["categories"]

        def counts(name):
            c = categories[name]
            return c["truth"], c["found"], c["truePositives"], c["falsePositives"], c["falseNegatives"]

        self.assertEqual(counts("stale-context:decision"), (1, 2, 1, 1, 0))
        self.assertEqual(counts("missing-context:decision"), (1, 0, 0, 0, 1))
        self.assertEqual(counts("duplicate-replay:delivery"), (1, 1, 1, 0, 0))
        self.assertEqual(counts("conflicting-values:delivery"), (1, 1, 1, 0, 0))
        self.assertEqual(counts("stale-context:regression"), (1, 1, 1, 0, 0))
        self.assertEqual(counts("delayed-events:pair"), (1, 1, 1, 0, 0))
        self.assertEqual(categories["stale-context:decision"]["precision"], 0.5)
        self.assertIsNone(categories["missing-context:decision"]["precision"])
        self.assertEqual(result["anomalies"], {})
        self.assertEqual(result["counts"]["decisions"], 4)

    def test_decision_categories_are_split_by_phase(self):
        result = self.evaluate([finding("stale-context", decision_id="d1"), finding("stale-context", decision_id="d2")])
        phases = result["categoriesByDecisionPhase"]
        self.assertEqual(phases["fixed"]["stale-context:decision"]["truePositives"], 1)
        self.assertEqual(phases["saturation"]["stale-context:decision"]["falsePositives"], 1)
        self.assertNotIn("duplicate-replay:delivery", phases["fixed"])

    def test_late_arrival_within_the_same_microsecond_is_ordered_by_nanoseconds(self):
        FixtureRun(self.temp.name, [finding("delayed-events", decision_id="d5", event_id="e1")])
        # d5 reflects version 1 and is decided at 2600.100 µs; e1 first arrives at 2600.300 µs.
        write_jsonl(os.path.join(self.temp.name, "decisions-1.jsonl"), [decision("d5", 1, 2600, 1, 1000, nanos=100)])
        write_jsonl(os.path.join(self.temp.name, "arrivals.jsonl"), [arrival("e1", 1, 2, 2000, 2600, "pa", nanos=300)])
        pairs = evaluator.evaluate(self.temp.name)["categories"]["delayed-events:pair"]
        # d1 also missed e1. With microseconds alone, d5's pair would be a tie and not counted.
        self.assertEqual((pairs["truth"], pairs["truePositives"], pairs["falsePositives"]), (2, 1, 0))

    def test_version_lag_tolerance_changes_stale_truth(self):
        result = self.evaluate([], lag=1)
        self.assertNotIn("stale-context:decision", result["categories"])

    def test_a_read_newer_than_the_primary_is_an_anomaly(self):
        FixtureRun(self.temp.name, [])
        write_jsonl(os.path.join(self.temp.name, "decisions-1.jsonl"), [decision("d9", 1, 2100, 3, 3000)])
        result = evaluator.evaluate(self.temp.name)
        self.assertEqual(result["anomalies"], {"readNewerThanPrimary": 1})

    def test_unreadable_logs_name_the_file_and_line(self):
        FixtureRun(self.temp.name, [])
        with open(os.path.join(self.temp.name, "applies.jsonl"), "a", encoding="utf-8") as handle:
            handle.write("{not json\n")
        with self.assertRaisesRegex(ValueError, r"applies.jsonl:4"):
            evaluator.evaluate(self.temp.name)

    def test_missing_logs_are_reported(self):
        FixtureRun(self.temp.name, [])
        os.remove(os.path.join(self.temp.name, "commits.jsonl"))
        with self.assertRaises(FileNotFoundError):
            evaluator.evaluate(self.temp.name)


def fake_run(mode, latency_p50, stale_truth, stale_found, repetition=1):
    metrics = {
        "decisions": {"latencyMicros": {"p50": latency_p50}},
        "events": {"sendFailures": 0},
        "detector": None if mode == "off" else {"checkFailures": 0, "sinkFailures": 0, "droppedChecks": 0},
    }
    categories = {"stale-context:decision": {"truth": stale_truth, "found": stale_found,
                                             "truePositives": min(stale_truth, stale_found)}}
    evaluation = {"categories": categories, "categoriesByDecisionPhase": {"fixed": categories}, "anomalies": {}}
    return {"repetition": repetition, "mode": mode, "metrics": metrics, "evaluation": evaluation}


class RunSystemBenchmarkTest(unittest.TestCase):
    def test_summary_takes_medians_and_pools_detection(self):
        runs = [
            fake_run("off", 100, 10, 0, 1), fake_run("off", 300, 10, 0, 2), fake_run("off", 200, 10, 0, 3),
            fake_run("on", 400, 10, 9, 1), fake_run("on", 500, 10, 10, 2), fake_run("on", 450, 10, 10, 3),
        ]
        summary = runner.summarize(runs)
        latency = next(row for row in summary["performance"] if row["metric"] == "Decision latency p50")
        self.assertEqual(latency["off"], {"median": 200, "min": 100, "max": 300, "runs": 3})
        self.assertEqual(latency["on"]["median"], 450)
        self.assertEqual(latency["change:on"], 250)
        self.assertAlmostEqual(latency["relativeChange:on"], 1.25)
        self.assertIsNone(latency["on-async"])
        stale = summary["detection"]["on"]["stale-context:decision"]
        self.assertEqual((stale["truth"], stale["found"], stale["truePositives"]), (30, 29, 29))
        self.assertAlmostEqual(stale["recall"], 29 / 30)
        self.assertEqual(summary["detectionByDecisionPhase"]["on"]["fixed"]["stale-context:decision"]["truth"], 30)

        report = runner.markdown(summary, {"sourceCommit": "abc", "sourceDirty": False, "processors": 4,
                                           "javaVersion": "17"}, {
            "repetitions": 3, "seed": 42, "users": 10, "eventsPerSecond": 1, "decisionsPerSecond": 1,
            "warmupSeconds": 1, "measureSeconds": 1, "saturationSeconds": 1})
        self.assertIn("Not production validation", report)
        self.assertIn("| Decision latency p50 | 200 µs | 450 µs (+125%) | n/a |", report)
        self.assertIn("| Stale read at decision | 30 | 29 | 1.0000 | 0.9667 | 0.9667 |  |", report)

    def test_smoke_settings_are_short_and_single(self):
        args = runner.parse_args(["--smoke"])
        self.assertEqual((args.repetitions, args.warmup_seconds, args.measure_seconds), (1, 3, 5))
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            runner.parse_args(["--repetitions", "0"])

    def test_formatting(self):
        self.assertEqual(runner.fmt(1500, "µs"), "1.50 ms")
        self.assertEqual(runner.fmt(3 * 1024 * 1024, "bytes"), "3.0 MiB")
        self.assertEqual(runner.fmt(12.34, "µs"), "12.3 µs")
        self.assertEqual(runner.fmt(None, "µs"), "n/a")


if __name__ == "__main__":
    unittest.main()
