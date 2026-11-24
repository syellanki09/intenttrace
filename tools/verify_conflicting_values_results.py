"""Recompute the fixed version-1 conflicting-values demonstration from the recorded source observations."""

import argparse
from pathlib import Path

from fixed_scenario import load_fixed_records
from verify_results import exact_integer, instant_order

PRIMARY = ("primary", 7, "2026-01-01T00:04:00Z", "500")
CACHE = {"agreeing": ("cache", 7, "2026-01-01T00:04:00Z", "500"),
         "newer-divergent": ("cache", 8, "2026-01-01T00:06:00Z", "750"),
         "same-version-divergent": ("cache", 7, "2026-01-01T00:04:00Z", "750")}
CASES = [("control", "last-arrival", "agreeing"), ("last-arrival-wins", "last-arrival", "newer-divergent"),
         ("recorded-resolution", "prefer-primary-and-record", "newer-divergent"),
         ("same-version-divergence", "last-arrival", "same-version-divergent")]


def observation(row):
    if set(row) != {"source", "version", "committedAt", "value"} or type(row["version"]) is not int:
        raise ValueError("Malformed source observation")
    return row["source"], row["version"], instant_order(row["committedAt"]), row["value"]


def fixed(entry):
    return entry[0], entry[1], instant_order(entry[2]), entry[3]


def verify_conflicting_values_results(path):
    records = load_fixed_records(path, "conflicting-values", len(CASES))
    for record, (case_id, policy, cache) in zip(records, CASES):
        if not exact_integer(record["schemaVersion"], 1) or record["caseId"] != case_id or record["policy"] != policy:
            raise ValueError("Invalid conflicting-values schema, case order, or policy")
        if (record["factKey"] != "account-17/credit-limit"
                or instant_order(record["decidedAt"]) != instant_order("2026-01-01T00:08:00Z")):
            raise ValueError("Decision identity disagrees with the fixed experiment")
        observations = [observation(row) for row in record["observations"]]
        if observations != [fixed(PRIMARY), fixed(CACHE[cache])]:
            raise ValueError("Source observations disagree with the injected values or arrival order")
        conflict = len({row[3] for row in observations}) > 1
        used = observations[0] if policy == "prefer-primary-and-record" else observations[-1]
        resolution = ({"rule": "prefer-primary", "selectedSource": "primary"}
                      if conflict and policy == "prefer-primary-and-record" else None)
        if (record["usedSource"], record["usedValue"]) != (used[0], used[3]):
            raise ValueError("Used value disagrees with the selection policy")
        if record["resolution"] != resolution:
            raise ValueError("Resolution record disagrees with the selection policy")
        satisfied = not conflict or (resolution is not None and resolution["selectedSource"] == used[0])
        status = "SATISFIED" if satisfied else "VIOLATED"
        if (record["status"] != status or record["failureClassId"] != "conflicting-values"
                or record["detected"] is not None):
            raise ValueError("Incorrect conflicting-values evaluation or claimed detection")
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    try:
        verify_conflicting_values_results(args.path)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Invalid conflicting-values results: {error}\n")
    print("Verified control, two silent-pick violations, and recorded resolution; detection remains unknown.")


if __name__ == "__main__":
    main()
