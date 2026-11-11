# Duplicate Replay Demonstration

This fixed synthetic scenario tests whether each logical event contributes once
when delivery is repeated. It adds a runnable `duplicate-replay` example to the
event-time and replay milestone.

## Run and Verify

From the repository root with JDK 17 and Python 3.9+:

```bash
./gradlew :benchmarks:runReplayScenario --no-daemon
python3 tools/verify_replay_results.py benchmarks/build/results/duplicate-replay/results.jsonl
```

The Gradle task also accepts `--args='--output /path/to/results.jsonl'` and
`--args='--help'`. A source archive without Git can use `--source-commit FULL_SHA`;
the evidence marks that identity as caller-supplied. Git checkouts record their
actual source SHA and whether the working tree is dirty. No seed or configurable
workload is needed for this fixed scenario.

## Controlled Input

Two events add synthetic interest points: `interest-a` adds 3 and `interest-b`
adds 5. Injection appends a second delivery of `interest-a` with the same payload.
Each execution uses fresh accumulator state. The decision boundary is after the
entire listed delivery sequence has been processed.

| Case | Delivery sequence | Policy | Observed total | Expected total | Evaluation |
| --- | --- | --- | --- | --- | --- |
| `control` | a, b | Apply every delivery | 8 | 8 | SATISFIED |
| `naive-replay` | a, b, a | Apply every delivery | 11 | 8 | VIOLATED |
| `idempotent-replay` | a, b, a | Deduplicate by event ID | 8 | 8 | SATISFIED |

All deliveries complete normally. The naive accumulator silently counts the
same logical contribution twice. The in-memory deduplicating accumulator skips
the repeated identity. This is mitigation behavior; it emits no detection signal.
`detected` is null in all three records.

## Evidence and Invariant

Each line uses replay-specific schema version 1 and contains source/runtime
metadata, case and policy, ordered delivery IDs and point values, per-delivery
application markers, expected/observed totals, canonical `duplicate-replay`
failure class, evaluation status, and the separate detection observation.
Delivery order is represented by array order. No event or ingestion timestamps
are fabricated for this example.

`ReplayInvariant` computes the expected total from unique event identities and
requires exactly one applied contribution per identity as well as the correct
observed total. It rejects inconsistent payloads sharing an identity and
applications without delivery evidence. The Python verifier separately
recomputes the fixed stream, application policy, totals, and evaluation from
the serialized evidence, rather than trusting the recorded status.

CI runs the scenario, verifies its JSONL, and retains `duplicate-replay-SHA`
artifacts for 30 days. The released five-case `preview.zip` format and its
generation command continue to cover the context and contract demonstrations;
this new scenario has a separate JSONL command and verifier.

## Scope

The example demonstrates repeated delivery and in-memory idempotency only. It
does not model durable deduplication, restarts, concurrent consumers, retention
windows, broker delivery guarantees, event-time reordering, or reconciliation.
It is not a claim of production exactly-once processing. Different payloads with
the same event identity belong to conflict handling and are rejected here.
