"""Checks shared by the verifiers of fixed, seedless synthetic scenarios."""

import re
from pathlib import Path

from verify_results import load_json


def load_fixed_records(path, scenario, count):
    """Load JSON Lines and check that every record names the scenario and shares one reproduction identity."""
    records = [load_json(line) for line in Path(path).read_text(encoding="utf-8").splitlines()]
    if len(records) != count:
        raise ValueError(f"Expected exactly {count} {scenario} records")
    for record in records:
        metadata = record["reproduction"]
        if metadata["scenarioName"] != scenario or metadata["scenarioVersion"] != "1":
            raise ValueError(f"Unexpected {scenario} scenario identity")
        if not isinstance(metadata["sourceCommitSha"], str) or re.fullmatch(
                r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", metadata["sourceCommitSha"]) is None:
            raise ValueError("Invalid source commit")
        for key in ("runtime", "jdk", "operatingSystem"):
            if not isinstance(metadata[key], str) or not metadata[key].strip():
                raise ValueError(f"Missing runtime metadata: {key}")
        if metadata["seed"] is not None or metadata != records[0]["reproduction"]:
            raise ValueError("Fixed cases require matching metadata and no seed")
        config = metadata["configuration"]
        if config.get("sourceDirty") not in ("true", "false") and config.get("sourceCommitOrigin") != "caller-supplied":
            raise ValueError("Missing source identity qualification")
    return records
