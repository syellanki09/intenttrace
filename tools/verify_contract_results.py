"""Independently recompute the fixed version-1 duration contract experiment from JSONL evidence."""

import argparse
import re
from pathlib import Path

from verify_results import exact_integer, load_json


def verify_contract_results(path):
    records = [load_json(line) for line in Path(path).read_text(encoding="utf-8").splitlines()]
    cases = [("compatible-upgrade", "SECONDS", 2), ("unit-drift", "MILLISECONDS", 2),
             ("same-version-drift", "MILLISECONDS", 1)]
    if len(records) != len(cases):
        raise ValueError("Expected exactly three contract records")
    for record, (case_id, unit, version) in zip(records, cases):
        metadata = record["reproduction"]
        if not exact_integer(record["schemaVersion"], 1) or record["caseId"] != case_id:
            raise ValueError("Invalid contract schema version or case order")
        if metadata["scenarioName"] != "contract-unit-drift" or metadata["scenarioVersion"] != "1":
            raise ValueError("Unexpected contract scenario identity")
        if not exact_integer(record["logicalDurationMillis"], 2000) or metadata["configuration"]["logicalDurationMillis"] != "2000":
            raise ValueError("Expected the fixed 2000-millisecond input")
        if re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", metadata["sourceCommitSha"]) is None:
            raise ValueError("Invalid source commit")
        for field in ("runtime", "jdk", "operatingSystem"):
            if not isinstance(metadata[field], str) or not metadata[field].strip():
                raise ValueError(f"Missing runtime metadata: {field}")
        if metadata["seed"] is not None:
            raise ValueError("Fixed contract demonstration must not claim a seed")
        configuration = metadata["configuration"]
        if configuration.get("sourceDirty") not in ("true", "false") and configuration.get("sourceCommitOrigin") != "caller-supplied":
            raise ValueError("Missing source identity qualification")
        reference_wire = load_json(record["referenceWirePayload"])
        observed_wire = load_json(record["wirePayload"])
        expected_wire_value = 2 if unit == "SECONDS" else 2000
        for wire, wire_version, value in [(reference_wire, 1, 2000), (observed_wire, version, expected_wire_value)]:
            if wire["inputId"] != "session-duration-001" or not exact_integer(wire["contractVersion"], wire_version) or not exact_integer(wire["duration"], value):
                raise ValueError("Wire payload disagrees with producer input/version/unit")
        if record["producerUnit"] != unit or record["consumerUnit"] != "SECONDS":
            raise ValueError("Unexpected producer/consumer unit assumption")
        expected_reference = f"durationMillis:{reference_wire['duration']}"
        expected_observed = f"durationMillis:{observed_wire['duration'] * 1000}"
        for observation, client, client_version, outcome in [
            (record["reference"], "web", 1, expected_reference),
            (record["observed"], "mobile", version, expected_observed),
        ]:
            if observation["inputId"] != "session-duration-001" or observation["clientId"] != client or not exact_integer(observation["contractVersion"], client_version) or observation["outcome"] != outcome:
                raise ValueError("Client observation disagrees with independently interpreted wire evidence")
        status = "SATISFIED" if expected_reference == expected_observed else "VIOLATED"
        if record["status"] != status or record["failureClassId"] != "contract-incompatibility" or record["detected"] is not None:
            raise ValueError("Incorrect evaluation or claimed detection")
        if metadata != records[0]["reproduction"]:
            raise ValueError("Contract cases must share reproduction metadata")
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    try:
        verify_contract_results(args.path)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Invalid contract results: {error}\n")
    print("Verified compatible upgrade and both unit-drift cases; detection remains unknown.")


if __name__ == "__main__":
    main()
