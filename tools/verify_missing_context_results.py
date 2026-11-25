"""Recompute the fixed version-1 missing-context demonstration from the recorded read and fallback evidence."""

import argparse
from pathlib import Path

from fixed_scenario import load_fixed_records
from verify_results import exact_integer, instant_order

CASES = [("control", "default-silently", False), ("silent-default", "default-silently", True),
         ("recorded-degradation", "default-and-record", True)]
REQUIRED = (4, "2026-01-01T00:05:00Z", "gold")
DEGRADATION = {"reason": "context-source-empty", "source": "tier-service"}


def snapshot(row):
    if set(row) != {"version", "committedAt", "value"} or type(row["version"]) is not int:
        raise ValueError("Malformed context snapshot")
    return row["version"], instant_order(row["committedAt"]), row["value"]


def verify_missing_context_results(path):
    records = load_fixed_records(path, "missing-context", len(CASES))
    required = (REQUIRED[0], instant_order(REQUIRED[1]), REQUIRED[2])
    for record, (case_id, policy, source_empty) in zip(records, CASES):
        if not exact_integer(record["schemaVersion"], 1) or record["caseId"] != case_id or record["policy"] != policy:
            raise ValueError("Invalid missing-context schema, case order, or policy")
        if (record["contextKey"] != "member-42/discount-tier" or record["defaultValue"] != "standard"
                or instant_order(record["decidedAt"]) != instant_order("2026-01-01T00:08:00Z")):
            raise ValueError("Decision identity disagrees with the fixed experiment")
        if snapshot(record["required"]) != required:
            raise ValueError("Required context disagrees with the fixed experiment")
        delivered = None if record["delivered"] is None else snapshot(record["delivered"])
        if delivered != (None if source_empty else required):
            raise ValueError("Delivered context disagrees with the injected source response")
        used = required[2] if delivered is not None else "standard"
        degradation = DEGRADATION if delivered is None and policy == "default-and-record" else None
        if record["usedValue"] != used:
            raise ValueError("Used value disagrees with the read and fallback policy")
        if record["degradation"] != degradation:
            raise ValueError("Degradation record disagrees with the fallback policy")
        if delivered is not None:
            status = "SATISFIED" if used == delivered[2] else "VIOLATED"
        else:
            status = "SATISFIED" if degradation is not None else "VIOLATED"
        if record["status"] != status or record["failureClassId"] != "missing-context" or record["detected"] is not None:
            raise ValueError("Incorrect missing-context evaluation or claimed detection")
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    try:
        verify_missing_context_results(args.path)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Invalid missing-context results: {error}\n")
    print("Verified control, silent-default violation, and recorded degradation; detection remains unknown.")


if __name__ == "__main__":
    main()
