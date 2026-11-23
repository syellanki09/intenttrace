#!/usr/bin/env python3
"""Score one system-benchmark run against ground truth computed from its own logs.

The benchmark JVM writes what each part of the synthetic host did: what the
producer committed to the primary, what the consumer received and applied to the
cache, and what each decision read. This script derives ground truth from those
logs alone, without the detector's code or state, and then compares it with the
detector's findings (``findings.jsonl``), class by class.

Ground truth, per class:

- stale-context (decision): the decision read version v, and the latest version
  committed to the primary at or before the decision is newer than v by more
  than ``maxVersionLag``.
- missing-context (decision): the decision read nothing, although a version had
  been committed.
- stale-context (state regression): the consumer applied an event that occurred
  before the event it replaced for the same user.
- duplicate-replay / conflicting-values (delivery): an event identity arrived
  again with the same / a different payload than its first arrival.
- delayed-events (decision, event): an event occurred at or before a decision,
  was newer than what the decision reflected, and first arrived after it.

Usage: evaluate_system_benchmark.py RUN_DIR [--output FILE]
"""

from __future__ import annotations

import argparse
import bisect
import glob
import json
import os
import sys
from collections import Counter, defaultdict
from typing import Dict, Iterable, Iterator, List, Optional, Tuple

SCHEMA_VERSION = 1
FRESHNESS_CLASSES = ("stale-context", "missing-context", "conflicting-values")


def read_jsonl(path: str) -> Iterator[dict]:
    with open(path, encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"{path}:{number}: {error}") from error


def read_many(run_dir: str, pattern: str) -> Iterator[dict]:
    paths = sorted(glob.glob(os.path.join(run_dir, pattern)))
    if not paths:
        raise FileNotFoundError(f"No files match {pattern} in {run_dir}")
    for path in paths:
        yield from read_jsonl(path)


def score(truth: Counter, found: Counter) -> dict:
    """Multiset comparison: each key may occur several times on either side."""
    tp = sum(min(count, found[key]) for key, count in truth.items())
    truth_total = sum(truth.values())
    found_total = sum(found.values())
    return {
        "truth": truth_total,
        "found": found_total,
        "truePositives": tp,
        "falsePositives": found_total - tp,
        "falseNegatives": truth_total - tp,
        "precision": round(tp / found_total, 6) if found_total else None,
        "recall": round(tp / truth_total, 6) if truth_total else None,
    }


class Commits:
    """Versions committed to the primary, per user, in commit order."""

    def __init__(self, records: Iterable[dict]):
        per_user: Dict[int, List[Tuple[int, int]]] = defaultdict(list)
        for record in records:
            per_user[record["user"]].append((record["committedMicros"], record["version"]))
        self.times: Dict[int, List[int]] = {}
        self.versions: Dict[int, List[int]] = {}
        for user, entries in per_user.items():
            entries.sort()
            self.times[user] = [t for t, _ in entries]
            self.versions[user] = [v for _, v in entries]

    def latest_at(self, user: int, micros: int) -> Optional[int]:
        times = self.times.get(user)
        if not times:
            return None
        index = bisect.bisect_right(times, micros)
        return self.versions[user][index - 1] if index else None


def evaluate(run_dir: str) -> dict:
    with open(os.path.join(run_dir, "config.json"), encoding="utf-8") as handle:
        config = json.load(handle)
    lag = int(config["maxVersionLag"])

    commits = Commits(list(read_many(run_dir, "commits-preload.jsonl")) + list(read_many(run_dir, "commits.jsonl")))
    decisions = list(read_many(run_dir, "decisions-*.jsonl"))
    arrivals = list(read_jsonl(os.path.join(run_dir, "arrivals.jsonl")))
    applies = list(read_jsonl(os.path.join(run_dir, "applies.jsonl")))
    findings = list(read_jsonl(os.path.join(run_dir, "findings.jsonl")))

    truth: Dict[str, Counter] = defaultdict(Counter)
    anomalies = Counter()

    # Decisions: freshness against the primary's commit history.
    for decision in decisions:
        latest = commits.latest_at(decision["user"], decision["decidedMicros"])
        read = decision["version"]
        if read is None:
            if latest is not None:
                truth["missing-context:decision"][decision["decisionId"]] += 1
        elif latest is None or read > latest:
            anomalies["readNewerThanPrimary"] += 1
        elif latest - read > lag:
            truth["stale-context:decision"][decision["decisionId"]] += 1

    # Deliveries: repeated identities, compared with the first arrival's payload.
    # Arrival and decision times are compared in nanoseconds: an event can arrive
    # within the same microsecond as a decision. Commit times are whole microseconds.
    first_payload: Dict[Tuple[int, str], str] = {}
    first_ingest: Dict[Tuple[int, str], Tuple[int, int]] = {}
    for arrival in arrivals:
        identity = (arrival["user"], arrival["eventId"])
        occurred_nanos = arrival["occurredMicros"] * 1000
        if identity not in first_payload:
            first_payload[identity] = arrival["payload"]
            first_ingest[identity] = (occurred_nanos, arrival["ingestedNanos"])
            continue
        kind = "duplicate-replay" if arrival["payload"] == first_payload[identity] else "conflicting-values"
        truth[f"{kind}:delivery"][arrival["eventId"]] += 1
        occurred, ingested = first_ingest[identity]
        first_ingest[identity] = (occurred, min(ingested, arrival["ingestedNanos"]))

    # Applications: an older event replacing newer state for the same user.
    previous: Dict[int, int] = {}
    for applied in applies:
        before = previous.get(applied["user"])
        if before is not None and applied["occurredMicros"] < before:
            truth["stale-context:regression"][applied["eventId"]] += 1
        previous[applied["user"]] = applied["occurredMicros"]

    # Late arrivals: events in flight at a decision that it did not reflect.
    per_user_events: Dict[int, List[Tuple[int, int, str]]] = defaultdict(list)
    max_in_flight = 0
    for (user, event_id), (occurred, ingested) in first_ingest.items():
        per_user_events[user].append((occurred, ingested, event_id))
        max_in_flight = max(max_in_flight, ingested - occurred)
    occurred_index: Dict[int, List[int]] = {}
    for user, events in per_user_events.items():
        events.sort()
        occurred_index[user] = [e[0] for e in events]
    for decision in decisions:
        user = decision["user"]
        events = per_user_events.get(user)
        if not events:
            continue
        decided = decision["decidedNanos"]
        reflected = None if decision["occurredMicros"] is None else decision["occurredMicros"] * 1000
        index = bisect.bisect_right(occurred_index[user], decided) - 1
        while index >= 0:
            occurred, ingested, event_id = events[index]
            if decided - occurred > max_in_flight or (reflected is not None and occurred <= reflected):
                break
            if ingested > decided:
                truth["delayed-events:pair"][(decision["decisionId"], event_id)] += 1
            index -= 1

    found: Dict[str, Counter] = defaultdict(Counter)
    confidence = Counter()
    for finding in findings:
        cls = finding["failureClassId"]
        confidence[(cls, finding["confidence"])] += 1
        decision_id = finding.get("decisionId")
        event_id = finding.get("eventId")
        if cls == "delayed-events":
            found["delayed-events:pair"][(decision_id, event_id)] += 1
        elif decision_id is not None and cls in FRESHNESS_CLASSES:
            found[f"{cls}:decision"][decision_id] += 1
        elif cls == "stale-context" and event_id is not None:
            found["stale-context:regression"][event_id] += 1
        elif cls in ("duplicate-replay", "conflicting-values") and event_id is not None:
            found[f"{cls}:delivery"][event_id] += 1
        else:
            found[f"{cls}:other"][decision_id or event_id or "-"] += 1

    categories = sorted(set(truth) | set(found))
    by_category = {name: score(truth[name], found[name]) for name in categories}

    # Decision-keyed categories, split by the phase that issued the decision.
    phase_of = {d["decisionId"]: d["phase"] for d in decisions}

    def decision_of(key):
        return key[0] if isinstance(key, tuple) else key

    by_phase: Dict[str, Dict[str, dict]] = {}
    for name in categories:
        if not (name.endswith(":decision") or name.endswith(":pair")):
            continue
        for phase in sorted(set(phase_of.values())):
            keep = lambda counter: Counter({k: v for k, v in counter.items() if phase_of.get(decision_of(k)) == phase})
            by_phase.setdefault(phase, {})[name] = score(keep(truth[name]), keep(found[name]))
    return {
        "schemaVersion": SCHEMA_VERSION,
        "runId": config["runId"],
        "mode": config["mode"],
        "maxVersionLag": lag,
        "counts": {
            "decisions": len(decisions),
            "arrivals": len(arrivals),
            "applies": len(applies),
            "findings": len(findings),
            "maxInFlightMicros": max_in_flight // 1000,
        },
        "anomalies": dict(anomalies),
        "categories": by_category,
        "categoriesByDecisionPhase": by_phase,
        "findingsByConfidence": {f"{cls}:{level}": n for (cls, level), n in sorted(confidence.items())},
    }


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("run_dir")
    parser.add_argument("--output", help="where to write evaluation.json (default: RUN_DIR/evaluation.json)")
    args = parser.parse_args(argv)
    result = evaluate(args.run_dir)
    output = args.output or os.path.join(args.run_dir, "evaluation.json")
    with open(output, "w", encoding="utf-8") as handle:
        json.dump(result, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print(json.dumps(result["categories"], indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
