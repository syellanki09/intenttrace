"""Check the version-1 stale-context demonstration output using only Python's stdlib."""

import argparse
import calendar
import json
import re
from pathlib import Path


def load_json(value):
    """Reject ambiguous keys and non-JSON numeric constants instead of normalizing them."""
    def unique_object(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                raise ValueError(f"Duplicate JSON key: {key}")
            result[key] = item
        return result

    def invalid_constant(constant):
        raise ValueError(f"Invalid JSON numeric constant: {constant}")

    return json.loads(value, object_pairs_hook=unique_object, parse_constant=invalid_constant)


def exact_integer(value, expected):
    return type(value) is int and value == expected


def instant_order(value):
    """Compare Java Instant UTC output without losing nanoseconds or extended years."""
    if not isinstance(value, str):
        raise ValueError("Timestamp must be an ISO-8601 UTC string")
    match = re.fullmatch(r"([+-]?\d{4,})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z", value)
    if match is None:
        raise ValueError(f"Invalid UTC timestamp: {value}")
    year, month, day, hour, minute, second = map(int, match.groups()[:6])
    if not (-1000000000 <= year <= 1000000000 and 1 <= month <= 12):
        raise ValueError(f"Invalid timestamp year or month: {value}")
    if not (1 <= day <= calendar.monthrange(year, month)[1] and
            0 <= hour <= 23 and 0 <= minute <= 59 and 0 <= second <= 59):
        raise ValueError(f"Invalid timestamp date or time: {value}")
    nanos = int((match.group(7) or "").ljust(9, "0"))
    return year, month, day, hour, minute, second, nanos


def verify(path):
    records = [load_json(line) for line in Path(path).read_text(encoding="utf-8").splitlines()]
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
        older_time, latest_time, decision_time = (
            instant_order(configuration[key]) for key in ("olderCommittedAt", "latestCommittedAt", "decidedAt")
        )
        if not older_time <= latest_time <= decision_time:
            raise ValueError("Configured commit times must be ordered and eligible at the decision boundary")
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
            "schema version": exact_integer(record["schemaVersion"], 1),
            "execution": record["execution"] == execution,
            "used version": exact_integer(used["version"], version),
            "latest version": exact_integer(latest["version"], latest_version),
            "context identities": used["id"] == latest["id"] == record["contextId"] == context_id,
            "outcome": record["outcome"] == outcome == used["attributes"]["preferredCategory"],
            "failure class": evaluation["failureClassId"] == "stale-context",
            "invariant status": evaluation["status"] == status,
            "evaluation versions": exact_integer(evaluation["observedVersion"], version)
                and exact_integer(evaluation["expectedVersion"], latest_version),
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
