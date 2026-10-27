"""Check the version-1 stale-context demonstration output using only Python's stdlib."""

import argparse
import json
from pathlib import Path


def verify(path):
    records = [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines()]
    if len(records) != 2:
        raise ValueError("Expected exactly two JSONL records")
    configuration = records[0]["reproduction"].get("configuration", {})
    if "configurationVersion" in configuration:
        if configuration["configurationVersion"] != "1":
            raise ValueError("Unsupported scenario configuration version")
        older, latest_version = int(configuration["olderVersion"]), int(configuration["latestVersion"])
        context_id = configuration["contextId"]
        older_category, latest_category = configuration["olderCategory"], configuration["latestCategory"]
        if not (0 < older < latest_version) or not context_id.strip():
            raise ValueError("Invalid configured versions or context ID")
        if not older_category.strip() or not latest_category.strip() or older_category == latest_category:
            raise ValueError("Configured categories must be nonblank and distinct")
        if configuration["pinnedVersion"] != str(older):
            raise ValueError("Pinned version disagrees with configured older version")
    else:
        older, latest_version, context_id = 1, 2, "user-001"
        older_category, latest_category = "travel", "technology"
    expected = [("CONTROL", latest_version, latest_category, "SATISFIED"),
                ("INJECTED", older, older_category, "VIOLATED")]
    for record, (execution, version, outcome, status) in zip(records, expected):
        evaluation = record["evaluation"]
        used = record["usedContext"]
        latest = record["latestEligibleContext"]
        checks = {
            "schema version": record["schemaVersion"] == 1,
            "execution": record["execution"] == execution,
            "used version": used["version"] == version,
            "latest version": latest["version"] == latest_version,
            "context identities": used["id"] == latest["id"] == record["contextId"] == context_id,
            "outcome": record["outcome"] == outcome == used["attributes"]["preferredCategory"],
            "failure class": evaluation["failureClassId"] == "stale-context",
            "invariant status": evaluation["status"] == status,
            "evaluation versions": evaluation["observedVersion"] == version and evaluation["expectedVersion"] == latest_version,
            "unknown detection": evaluation["detected"] is None,
            "no degradation": record["degradationReason"] is None,
            "scenario": record["reproduction"]["scenarioName"] == "stale-context"
                        and record["reproduction"]["scenarioVersion"] == "1",
        }
        if "configurationVersion" in configuration:
            checks.update({
                "decision time": record["decidedAt"] == configuration["decidedAt"],
                "used commit time": used["committedAt"] == configuration[
                    "latestCommittedAt" if execution == "CONTROL" else "olderCommittedAt"],
                "latest evidence": latest["committedAt"] == configuration["latestCommittedAt"]
                    and latest["attributes"]["preferredCategory"] == latest_category,
            })
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
