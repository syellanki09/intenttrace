"""Recompute the fixed version-1 duplicate-replay demonstration from delivery evidence."""

import argparse
import re
from pathlib import Path

from verify_results import exact_integer, load_json


def verify_replay_results(path):
    records = [load_json(line) for line in Path(path).read_text(encoding="utf-8").splitlines()]
    cases = [("control", False, False), ("naive-replay", True, False), ("idempotent-replay", True, True)]
    if len(records) != len(cases):
        raise ValueError("Expected exactly three replay records")
    for record, (case_id, replayed, deduplicate) in zip(records, cases):
        if not exact_integer(record["schemaVersion"], 1) or record["caseId"] != case_id:
            raise ValueError("Invalid replay schema version or case order")
        metadata = record["reproduction"]
        if metadata["scenarioName"] != "duplicate-replay" or metadata["scenarioVersion"] != "1":
            raise ValueError("Unexpected replay scenario identity")
        if re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", metadata["sourceCommitSha"]) is None:
            raise ValueError("Invalid source commit")
        for key in ("runtime", "jdk", "operatingSystem"):
            if not isinstance(metadata[key], str) or not metadata[key].strip():
                raise ValueError(f"Missing runtime metadata: {key}")
        if metadata["seed"] is not None or metadata != records[0]["reproduction"]:
            raise ValueError("Fixed replay cases require matching metadata and no seed")
        config = metadata["configuration"]
        if config.get("sourceDirty") not in ("true", "false") and config.get("sourceCommitOrigin") != "caller-supplied":
            raise ValueError("Missing source identity qualification")
        expected_stream = [("interest-a", 3), ("interest-b", 5)] + ([("interest-a", 3)] if replayed else [])
        if record["policy"] != ("deduplicate-by-event-id" if deduplicate else "apply-every-delivery"):
            raise ValueError("Unexpected application policy")
        if len(record["deliveries"]) != len(expected_stream):
            raise ValueError("Delivery count disagrees with fixed input")
        unique, counts, total = {}, {}, 0
        for row, (event_id, points) in zip(record["deliveries"], expected_stream):
            if row["eventId"] != event_id or not exact_integer(row["points"], points):
                raise ValueError("Delivery identity or payload disagrees with fixed input")
            applied = not deduplicate or event_id not in unique
            if type(row["applied"]) is not bool or row["applied"] != applied:
                raise ValueError("Applied marker disagrees with delivery policy")
            unique[event_id] = points
            if applied:
                total += points
                counts[event_id] = counts.get(event_id, 0) + 1
        expected = sum(unique.values())
        status = "SATISFIED" if total == expected and all(counts.get(key) == 1 for key in unique) else "VIOLATED"
        if not exact_integer(record["expectedTotal"], expected) or not exact_integer(record["observedTotal"], total):
            raise ValueError("Recorded totals disagree with delivery evidence")
        if record["status"] != status or record["failureClassId"] != "duplicate-replay" or record["detected"] is not None:
            raise ValueError("Incorrect replay evaluation or claimed detection")
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    try:
        verify_replay_results(args.path)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Invalid replay results: {error}\n")
    print("Verified control, naive replay violation, and idempotent replay; detection remains unknown.")


if __name__ == "__main__":
    main()
