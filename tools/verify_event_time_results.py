"""Independently verify the fixed version-1 event-time ordering and late-arrival experiment."""

import argparse
import re
from pathlib import Path

from verify_results import exact_integer, instant_order, load_json


def verify_event_time_results(path):
    records = [load_json(line) for line in Path(path).read_text(encoding="utf-8").splitlines()]
    cases = [("control", "arrival-order", 12, 22), ("reordered-arrival", "arrival-order", 32, 22),
             ("event-time-reconciliation", "event-time", 32, 22), ("late-arrival", "event-time", 12, 45)]
    if len(records) != len(cases):
        raise ValueError("Expected exactly four event-time records")

    def clock(second):
        return instant_order(f"2026-01-01T00:00:{second:02d}Z")

    for record, (case_id, policy, older_ingest, newer_ingest) in zip(records, cases):
        if not exact_integer(record["schemaVersion"], 1) or record["caseId"] != case_id or record["policy"] != policy:
            raise ValueError("Invalid event-time schema, case order, or policy")
        metadata = record["reproduction"]
        if metadata["scenarioName"] != "event-time-ordering" or metadata["scenarioVersion"] != "1":
            raise ValueError("Unexpected event-time scenario identity")
        if re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", metadata["sourceCommitSha"]) is None:
            raise ValueError("Invalid source commit")
        for field in ("runtime", "jdk", "operatingSystem"):
            if not isinstance(metadata[field], str) or not metadata[field].strip():
                raise ValueError(f"Missing runtime metadata: {field}")
        if metadata["seed"] is not None or metadata != records[0]["reproduction"]:
            raise ValueError("Fixed event-time cases require matching metadata and no seed")
        config = metadata["configuration"]
        if config.get("sourceDirty") not in ("true", "false") and config.get("sourceCommitOrigin") != "caller-supplied":
            raise ValueError("Missing source identity qualification")
        boundary = instant_order(record["decidedAt"])
        if boundary != clock(40):
            raise ValueError("Unexpected fixed decision boundary")
        expected_deliveries = sorted([
            ("interest-a", clock(10), clock(older_ingest), "travel"),
            ("interest-b", clock(20), clock(newer_ingest), "technology"),
        ], key=lambda row: row[2])
        deliveries = [(row["eventId"], instant_order(row["occurredAt"]), instant_order(row["ingestedAt"]), row["value"])
                      for row in record["deliveries"]]
        if deliveries != expected_deliveries:
            raise ValueError("Delivery history disagrees with the fixed experiment")
        expected = max((row for row in deliveries if row[1] <= boundary), key=lambda row: row[1])
        available = [row for row in deliveries if row[2] <= boundary]
        observed = max(available, key=lambda row: row[2] if policy == "arrival-order" else row[1])
        if (record["expectedEventId"], record["expectedValue"]) != (expected[0], expected[3]):
            raise ValueError("Expected event disagrees with logical event history")
        if (record["observedEventId"], record["observedValue"]) != (observed[0], observed[3]):
            raise ValueError("Observed event disagrees with available arrivals and selection policy")
        status = "SATISFIED" if observed == expected else "VIOLATED"
        failure_class = "delayed-events" if expected[2] > boundary else "stale-context"
        if record["status"] != status or record["failureClassId"] != failure_class or record["detected"] is not None:
            raise ValueError("Incorrect event-time evaluation, taxonomy, or claimed detection")
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    try:
        verify_event_time_results(args.path)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Invalid event-time results: {error}\n")
    print("Verified control, reordered-arrival violation, reconciliation, and late-arrival violation; detection remains unknown.")


if __name__ == "__main__":
    main()
