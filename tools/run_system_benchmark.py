#!/usr/bin/env python3
"""Run the Kafka and Redis backed synthetic benchmark with the detector off and on.

Each repetition runs three modes, each in a fresh JVM and in a rotating order,
against the same seeded workload: detector off, decision checks inline in the
decision path, and decision checks handed to a background checker. Every run is scored by
evaluate_system_benchmark.py, and the runs are summarized in summary.json and
summary.md: medians with min and max across repetitions for performance, and
pooled precision and recall for detection.

This is a synthetic benchmark on one machine. It measures how the detector
behaves under a controlled workload with known faults; it is not production
validation.

Usage:
  python3 tools/run_system_benchmark.py                 # 3 repetitions, defaults
  python3 tools/run_system_benchmark.py --smoke         # short run for CI
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import platform
import shutil
import statistics
import subprocess
import sys
from typing import Callable, Dict, List, Optional

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import evaluate_system_benchmark  # noqa: E402

COMPOSE = os.path.join(ROOT, "system-benchmark", "compose.yaml")
LAUNCHER = os.path.join(ROOT, "system-benchmark", "build", "install", "intenttrace-system-benchmark", "bin",
                        "intenttrace-system-benchmark")
MODES = ("off", "on", "on-async")
MODE_LABELS = {"off": "Detector off", "on": "Inline checks", "on-async": "Off-path checks"}

# (label, path into metrics.json, unit, whether lower is better)
PERFORMANCE = [
    ("Decision latency p50", ("decisions", "latencyMicros", "p50"), "µs", True),
    ("Decision latency p99", ("decisions", "latencyMicros", "p99"), "µs", True),
    ("Decision latency p99.9", ("decisions", "latencyMicros", "p999"), "µs", True),
    ("Decision service time p50", ("decisions", "serviceMicros", "p50"), "µs", True),
    ("Decision service time p99", ("decisions", "serviceMicros", "p99"), "µs", True),
    ("Decision CPU per decision", ("decisions", "cpuMicrosPerDecision"), "µs", True),
    ("Allocation per decision", ("decisions", "allocatedBytesPerDecision"), "bytes", True),
    ("Saturation throughput", ("decisions", "saturationThroughputPerSecond"), "decisions/s", False),
    ("Oracle call p50", ("oracle", "callMicros", "p50"), "µs", True),
    ("Oracle call p99", ("oracle", "callMicros", "p99"), "µs", True),
    ("Consumer CPU per event", ("events", "cpuMicrosPerEvent"), "µs", True),
    ("Allocation per event", ("events", "allocatedBytesPerEvent"), "bytes", True),
    ("Event propagation p50", ("events", "propagationMicros", "p50"), "µs", True),
    ("Event propagation p99", ("events", "propagationMicros", "p99"), "µs", True),
    ("Heap used after GC", ("memory", "heapUsedAfterGcBytes"), "bytes", True),
]

DETECTION_LABELS = {
    "stale-context:decision": "Stale read at decision",
    "missing-context:decision": "Missing context at decision",
    "stale-context:regression": "Older event replaced newer state",
    "duplicate-replay:delivery": "Duplicate delivery",
    "conflicting-values:delivery": "Reused event id, different payload",
    "delayed-events:pair": "Event arrived after a decision that needed it",
}


def lookup(document: dict, path) -> Optional[float]:
    value = document
    for part in path:
        if not isinstance(value, dict) or part not in value:
            return None
        value = value[part]
    return value


def spread(values: List[float]) -> Dict[str, float]:
    return {"median": statistics.median(values), "min": min(values), "max": max(values), "runs": len(values)}


def summarize(runs: List[dict]) -> dict:
    """Aggregates per-run metrics and evaluations. Each run is {mode, metrics, evaluation}."""
    performance = []
    for label, path, unit, lower_is_better in PERFORMANCE:
        row = {"metric": label, "unit": unit, "lowerIsBetter": lower_is_better}
        for mode in MODES:
            values = [lookup(r["metrics"], path) for r in runs if r["mode"] == mode]
            values = [v for v in values if v is not None]
            row[mode] = spread(values) if values else None
        for mode in MODES[1:]:
            if row["off"] and row[mode]:
                off, on = row["off"]["median"], row[mode]["median"]
                row[f"change:{mode}"] = on - off
                row[f"relativeChange:{mode}"] = (on - off) / off if off else None
        performance.append(row)

    detection = {mode: pool(r["evaluation"]["categories"] for r in runs if r["mode"] == mode) for mode in MODES}
    detection_by_phase = {
        mode: {
            phase: pool(r["evaluation"]["categoriesByDecisionPhase"].get(phase, {}) for r in runs if r["mode"] == mode)
            for phase in ("fixed", "saturation")
        }
        for mode in MODES
    }

    failures = {
        "checkFailures": sum(lookup(r["metrics"], ("detector", "checkFailures")) or 0 for r in runs),
        "droppedChecks": sum(lookup(r["metrics"], ("detector", "droppedChecks")) or 0 for r in runs),
        "sinkFailures": sum(lookup(r["metrics"], ("detector", "sinkFailures")) or 0 for r in runs),
        "sendFailures": sum(lookup(r["metrics"], ("events", "sendFailures")) or 0 for r in runs),
        "anomalies": sum(sum(r["evaluation"]["anomalies"].values()) for r in runs),
    }
    return {"performance": performance, "detection": detection, "detectionByDecisionPhase": detection_by_phase,
            "failures": failures}


def pool(evaluations) -> Dict[str, dict]:
    """Sums per-category counts across runs and recomputes precision and recall."""
    pooled: Dict[str, Dict[str, int]] = {}
    for categories in evaluations:
        for category, result in categories.items():
            totals = pooled.setdefault(category, {"truth": 0, "found": 0, "truePositives": 0})
            for key in totals:
                totals[key] += result[key]
    for totals in pooled.values():
        tp = totals["truePositives"]
        totals["falsePositives"] = totals["found"] - tp
        totals["falseNegatives"] = totals["truth"] - tp
        totals["precision"] = tp / totals["found"] if totals["found"] else None
        totals["recall"] = tp / totals["truth"] if totals["truth"] else None
    return dict(sorted(pooled.items()))


def fmt(value: Optional[float], unit: str) -> str:
    if value is None:
        return "n/a"
    if unit == "bytes" and abs(value) >= 1024 * 1024:
        return f"{value / (1024 * 1024):,.1f} MiB"
    if unit == "µs" and abs(value) >= 1000:
        return f"{value / 1000:,.2f} ms"
    if abs(value) >= 100:
        return f"{value:,.0f} {unit}"
    return f"{value:,.1f} {unit}"


def ratio(value: Optional[float]) -> str:
    return "n/a" if value is None else f"{value:.4f}"


def markdown(summary: dict, environment: dict, settings: dict) -> str:
    lines = [
        "# System benchmark summary",
        "",
        "Synthetic workload on one machine, Kafka and Redis in Docker. Not production validation.",
        "",
        f"- Source commit: `{environment['sourceCommit']}`{' (dirty tree)' if environment['sourceDirty'] else ''}",
        f"- Repetitions per mode: {settings['repetitions']}; seed {settings['seed']}",
        f"- Workload: {settings['eventsPerSecond']} events/s and {settings['decisionsPerSecond']} decisions/s "
        f"over {settings['users']} users; {settings['warmupSeconds']} s warmup, {settings['measureSeconds']} s "
        f"measured, {settings['saturationSeconds']} s saturation",
        f"- Host: {environment['processors']} processors, Java {environment['javaVersion']}",
        "",
        "## Overhead",
        "",
        "| Metric | " + " | ".join(MODE_LABELS[m] for m in MODES) + " |",
        "| --- |" + " --- |" * len(MODES),
    ]
    for row in summary["performance"]:
        cells = []
        for mode in MODES:
            if not row[mode]:
                cells.append("n/a")
                continue
            cell = fmt(row[mode]["median"], row["unit"])
            relative = row.get(f"relativeChange:{mode}")
            if relative is not None:
                cell += f" ({relative:+.0%})"
            cells.append(cell)
        lines.append(f"| {row['metric']} | " + " | ".join(cells) + " |")
    lines += [
        "",
        "Medians across repetitions, with the change from detector off in brackets. Min and max are in summary.json.",
    ]
    for mode in MODES[1:]:
        lines += [
            "",
            f"## Detection: {MODE_LABELS[mode].lower()} (pooled across repetitions)",
            "",
            "| Condition | Ground truth | Found | Precision | Recall | Recall at fixed rate | Recall at saturation |",
            "| --- | --- | --- | --- | --- | --- | --- |",
        ]
        phases = summary["detectionByDecisionPhase"].get(mode, {})
        for category, totals in summary["detection"].get(mode, {}).items():
            label = DETECTION_LABELS.get(category, category)
            per_phase = [ratio(phases.get(p, {}).get(category, {}).get("recall")) if category in phases.get(p, {})
                         else "" for p in ("fixed", "saturation")]
            lines.append(f"| {label} | {totals['truth']:,} | {totals['found']:,} | {ratio(totals['precision'])} "
                         f"| {ratio(totals['recall'])} | {per_phase[0]} | {per_phase[1]} |")
    off = summary["detection"].get("off", {})
    if off:
        lines += ["", "Ground truth with the detector off, for comparison: "
                  + "; ".join(f"{DETECTION_LABELS.get(c, c)} {t['truth']:,}" for c, t in off.items()) + "."]
    failures = summary["failures"]
    lines += ["", f"Check failures {failures['checkFailures']}, dropped checks {failures['droppedChecks']}, "
              f"sink failures {failures['sinkFailures']}, "
              f"send failures {failures['sendFailures']}, evaluator anomalies {failures['anomalies']}.", ""]
    return "\n".join(lines)


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()


def run(command: List[str], **kwargs) -> None:
    print("+", " ".join(command), flush=True)
    subprocess.run(command, cwd=ROOT, check=True, **kwargs)


def parse_args(argv: Optional[List[str]]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", default=os.path.join(ROOT, "build", "system-benchmark"))
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--users", type=int, default=10_000)
    parser.add_argument("--events-per-second", type=int, default=2_000)
    parser.add_argument("--decisions-per-second", type=int, default=2_000)
    parser.add_argument("--warmup-seconds", type=int, default=15)
    parser.add_argument("--measure-seconds", type=int, default=30)
    parser.add_argument("--saturation-seconds", type=int, default=10)
    parser.add_argument("--smoke", action="store_true", help="one short repetition, for CI")
    parser.add_argument("--no-compose", action="store_true", help="use Kafka and Redis that are already running")
    parser.add_argument("--down", action="store_true", help="stop the containers afterwards")
    args = parser.parse_args(argv)
    if args.smoke:
        args.repetitions, args.warmup_seconds, args.measure_seconds, args.saturation_seconds = 1, 3, 5, 2
    if args.repetitions < 1:
        parser.error("--repetitions must be at least 1")
    return args


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    if os.path.exists(args.output):
        shutil.rmtree(args.output)
    os.makedirs(args.output)
    if not args.no_compose:
        run(["docker", "compose", "-f", COMPOSE, "up", "-d", "--wait"])
    gradlew = os.path.join(ROOT, "gradlew")
    run([gradlew, ":system-benchmark:installDist", "--no-daemon", "-q"])

    settings = {
        "repetitions": args.repetitions, "seed": args.seed, "users": args.users,
        "eventsPerSecond": args.events_per_second, "decisionsPerSecond": args.decisions_per_second,
        "warmupSeconds": args.warmup_seconds, "measureSeconds": args.measure_seconds,
        "saturationSeconds": args.saturation_seconds,
    }
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    runs = []
    for repetition in range(1, args.repetitions + 1):
        shift = (repetition - 1) % len(MODES)
        order = MODES[shift:] + MODES[:shift]
        for mode in order:
            run_id = f"{stamp}-r{repetition}-{mode}"
            run_dir = os.path.join(args.output, f"r{repetition}-{mode}")
            run([LAUNCHER, f"--mode={mode}", f"--output={run_dir}", f"--run-id={run_id}",
                 f"--seed={args.seed}", f"--users={args.users}",
                 f"--events-per-second={args.events_per_second}",
                 f"--decisions-per-second={args.decisions_per_second}",
                 f"--warmup-seconds={args.warmup_seconds}", f"--measure-seconds={args.measure_seconds}",
                 f"--saturation-seconds={args.saturation_seconds}"])
            evaluation = evaluate_system_benchmark.evaluate(run_dir)
            with open(os.path.join(run_dir, "evaluation.json"), "w", encoding="utf-8") as handle:
                json.dump(evaluation, handle, indent=2, sort_keys=True)
                handle.write("\n")
            with open(os.path.join(run_dir, "metrics.json"), encoding="utf-8") as handle:
                metrics = json.load(handle)
            runs.append({"repetition": repetition, "mode": mode, "metrics": metrics, "evaluation": evaluation})

    first = runs[0]["metrics"]["environment"]
    environment = {
        "sourceCommit": git("rev-parse", "HEAD"),
        "sourceDirty": bool(git("status", "--porcelain")),
        "processors": first["availableProcessors"],
        "javaVersion": first["javaVersion"],
        "platform": platform.platform(),
    }
    summary = summarize(runs)
    document = {"schemaVersion": 1, "settings": settings, "environment": environment, **summary,
                "runs": [{"repetition": r["repetition"], "mode": r["mode"]} for r in runs]}
    with open(os.path.join(args.output, "summary.json"), "w", encoding="utf-8") as handle:
        json.dump(document, handle, indent=2, sort_keys=True)
        handle.write("\n")
    report = markdown(summary, environment, settings)
    with open(os.path.join(args.output, "summary.md"), "w", encoding="utf-8") as handle:
        handle.write(report)
    print(report)
    if args.down and not args.no_compose:
        run(["docker", "compose", "-f", COMPOSE, "down"])
    return 1 if summary["failures"]["checkFailures"] or summary["failures"]["sendFailures"] else 0


if __name__ == "__main__":
    sys.exit(main())
