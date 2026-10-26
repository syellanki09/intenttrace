"""Check the version-1 stale-context demonstration output using only Python's stdlib."""

import argparse
import json
from pathlib import Path


def verify(path):
    records = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines()]
    if len(records) != 2:
        raise ValueError("Expected exactly two JSONL records")
    expected = [("CONTROL", 2, "technology", "SATISFIED"),
                ("INJECTED", 1, "travel", "VIOLATED")]
    for record, (execution, version, outcome, status) in zip(records, expected):
        evaluation = record["evaluation"]
        used = record["usedContext"]
        latest = record["latestEligibleContext"]
        checks = {
            "schema version": record["schemaVersion"] == 1,
            "execution": record["execution"] == execution,
            "used version": used["version"] == version,
            "latest version": latest["version"] == 2,
            "context identities": used["id"] == latest["id"] == record["contextId"] == "user-001",
            "outcome": record["outcome"] == outcome == used["attributes"]["preferredCategory"],
            "failure class": evaluation["failureClassId"] == "stale-context",
            "invariant status": evaluation["status"] == status,
            "evaluation versions": evaluation["observedVersion"] == version and evaluation["expectedVersion"] == 2,
            "unknown detection": evaluation["detected"] is None,
            "no degradation": record["degradationReason"] is None,
            "scenario": record["reproduction"]["scenarioName"] == "stale-context"
                        and record["reproduction"]["scenarioVersion"] == "1",
        }
        for name, valid in checks.items():
            if not valid:
                raise ValueError(f"{execution}: unexpected {name}")
    if records[0]["reproduction"] != records[1]["reproduction"]:
        raise ValueError("Paired executions must share reproduction metadata")
    if records[0]["latestEligibleContext"] != records[1]["latestEligibleContext"]:
        raise ValueError("Paired executions must share latest eligible evidence")
    if records[0]["decidedAt"] != records[1]["decidedAt"]:
        raise ValueError("Paired executions must share the decision boundary")
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    try:
        verify(args.path)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Invalid scenario results: {error}\n")
    print("Verified CONTROL=SATISFIED, INJECTED=VIOLATED; detection remains unknown.")


if __name__ == "__main__":
    main()
