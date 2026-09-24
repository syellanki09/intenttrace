# A Taxonomy of Hidden Information-Integrity Failures

**Status:** working draft. Revised as reference implementation and benchmark results accumulate.

## Purpose and scope

This document defines six information-integrity failure classes for distributed personalization and recommendation systems that remain available and responsive while producing a decision from incorrect or insufficient state.

These failures share a property that makes them hard to manage: **the signals conventional monitoring collects are normal throughout.** Every service reports success. Latency is within bounds. Error rates are flat. The defect appears only in the *content* of the final decision, which most production monitoring does not evaluate.

Two consequences follow, and they motivate the taxonomy:

1. Detection cannot rely on availability signals. It requires comparing the information a decision used against the information it should have used.
2. Diagnosis cannot rely on error traces, because there is no error. It requires provenance — a record of which inputs, versions, and transformations produced the outcome.

**Out of scope.** Crashes, timeouts, saturation, and dropped connections are well covered by existing reliability tooling. They appear here only where they *cause* an information-integrity failure downstream: a timeout that silently yields an empty context is in scope, while the timeout itself is not.

## Classification

Each class below is stated as an invariant that the system violates, which is what makes it mechanically testable.

### Canonical class identifiers

Benchmark scenarios, injectors, result records, and documentation use the following
stable identifiers for the six top-level failure classes:

| ID | Failure class |
| --- | --- |
| `stale-context` | Stale context |
| `missing-context` | Missing context |
| `delayed-events` | Delayed events |
| `duplicate-replay` | Duplicate / replayed information |
| `conflicting-values` | Conflicting values |
| `contract-incompatibility` | Version / data-contract incompatibility |

These identifiers remain stable even if the explanatory text or individual
failure mechanisms evolve.

---

### 1. Stale context

**Invariant.** A decision reflects the most recent context committed before the decision point.

**Violation.** The decision uses a superseded value. The user acted; the system responded to a prior state.

**Mechanisms.** Cache TTL exceeding the rate of meaningful change; read replica lag; a materialized view rebuilt on a schedule rather than on write; a client holding an in-memory copy across a context change.

**Why monitoring misses it.** A cache hit on stale data is indistinguishable from a cache hit on fresh data. It is faster than a miss, so latency metrics improve as correctness degrades.

**Operational note.** Cache expiration is not the same thing as decision freshness. A Redis-style TTL can still consider an entry valid even after a newer context version has been committed. For that reason, IntentTrace carries the logical context version and commit time through the decision path instead of inferring freshness from cache hit/miss behavior or request latency.

**Observable signature.** Decision timestamp is later than a context commit that the decision did not incorporate.

**Injection approach.** Pin a cache entry, or hold a replica behind, across a context update, then issue a decision request.

**Detection question.** Does the system compare the age or version of the context it used against what was available, and surface the discrepancy?

---

### 2. Missing context

**Invariant.** When required context is unavailable, the system either fails visibly or degrades in a way that is recorded and attributable.

**Violation.** Context is absent and the system silently substitutes a default, an empty value, or a global fallback, producing a plausible but ungrounded result.

**Mechanisms.** A partial failure in a context service returning empty rather than erroring; an exception handler that swallows and defaults; a first-request path with no context yet materialized; a feature flag disabling a context source without notifying consumers.

**Why monitoring misses it.** The fallback path is usually a success path. It returns 200. The result looks like a generic recommendation, which is a legitimate output in other circumstances.

**Observable signature.** A decision whose provenance record contains no contributing context for a dimension the decision claims to personalize on.

**Injection approach.** Return empty payloads from one context source while leaving all health checks green.

**Detection question.** Can the system distinguish "no preference" from "preference unknown," and does it mark degraded decisions as degraded?

---

### 3. Delayed events

**Invariant.** Context generated before a decision point is available at that decision point.

**Violation.** The event exists and is valid but arrives after the decision that needed it. The system is eventually correct and momentarily wrong — at exactly the moment the user was looking.

**Mechanisms.** Queue backlog; consumer lag; batch windows wider than the user's session; retry backoff delaying a first delivery; cross-region replication lag.

**Why monitoring misses it.** Pipeline metrics measure throughput and eventual delivery, both of which look healthy. The lag is often well within alerting thresholds yet far outside the window that matters for a live session.

**Operational note.** In Kafka-style event pipelines, consumer lag is useful for operating the stream but does not answer the decision-level question: did this event become available before the decision that needed it? IntentTrace therefore keeps event time, ingestion time, and decision time separate rather than collapsing them into one timestamp.

**Observable signature.** Event-time precedes decision time; ingestion time follows it.

**Injection approach.** Introduce a controlled delay between event emission and availability, sweeping the delay across the session duration.

**Detection question.** Does the system evaluate context against event time rather than ingestion time, and can it report the gap?

---

### 4. Duplicate / replayed information

**Invariant.** Applying the same event more than once has the same effect as applying it once.

**Violation.** A repeated event compounds — a signal is counted twice, a recency score is inflated, a supersession is undone by a replayed older value.

**Mechanisms.** At-least-once delivery without deduplication; consumer restart replaying from a checkpoint; retry after a timed-out write that actually succeeded; backfill overlapping live traffic.

**Why monitoring misses it.** Duplicate processing is successful processing. It raises throughput. Nothing in the delivery path treats it as anomalous.

**Operational note.** At-least-once delivery makes redelivery a normal operating condition. The correctness requirement is not that a consumer never sees the same logical event twice; it is that reapplying that event cannot incorrectly change the resulting state. Offset rewinds, retries after ambiguous writes, and backfills that overlap live traffic are therefore modeled as distinct replay sources. Scenarios retain both logical event identity and ordering metadata so deduplication and recency handling can be evaluated separately.

**Observable signature.** More than one application of the same logical event identity; or an ordering inversion where an older value overwrites a newer one after a replay.

**Injection approach.** Re-deliver events with identical identity; replay a window that overlaps live traffic; re-order a supersession pair.

**Detection question.** Is there a durable notion of event identity, and is recency validation idempotent under re-delivery?

---

### 5. Conflicting values

**Invariant.** Where two sources describe the same fact, the system resolves the conflict by a defined rule and records that it did so.

**Violation.** Two sources disagree; the system picks one implicitly — by arrival order, by iteration order, by whichever wrote last — and no record survives that a conflict existed.

**Mechanisms.** The same fact maintained in two stores; a client-side value and a server-side value both considered authoritative; a merge that takes the last writer without comparing logical timestamps; two services computing the same derived value differently.

**Why monitoring misses it.** Both sources are healthy. Both values are well-formed. Only their disagreement is the defect, and nothing in the system is looking for disagreement.

**Observable signature.** Divergent values for one logical key at one decision point, with no conflict marker in the provenance record.

**Injection approach.** Write divergent values to two authoritative sources and issue a decision that consults both.

**Detection question.** Does the system detect disagreement, apply a stated resolution rule, and preserve evidence of the conflict for diagnosis?

---

### 6. Version / data-contract incompatibility

**Invariant.** Producer and consumer agree on both the shape and the meaning of the data they exchange.

**Violation.** A field is renamed, re-typed, made optional, given a new unit, or given new semantics under the same name. Data continues to flow. It is now interpreted incorrectly.

**Mechanisms.** A shared SDK upgraded on some clients and not others; a field whose units change without a name change; an enum gaining a member older consumers map to a default; optional fields added and silently ignored; mixed SDK versions across web and mobile.

**Why monitoring misses it.** Deserialization succeeds. The value is in range. Semantic drift produces no parse error and no schema violation — only a wrong answer.

**Operational note.** Syntactic compatibility does not guarantee semantic compatibility. A JSON field can continue to parse while its unit or meaning changes, and a Protobuf enum can gain a value that an older client sends down an unknown or default path. IntentTrace therefore compares decisions across client and contract versions rather than treating successful deserialization as proof of compatibility.

**Observable signature.** The same logical input produces divergent decisions across client versions, or across web and mobile clients on the same account state.

**Injection approach.** Run the reference pipeline against multiple SDK and schema versions simultaneously and compare decisions for identical inputs.

**Detection question.** Are compatibility and cross-client consistency verified before deployment, and can a decision be attributed to the contract version that produced it?

---

## Composite failures

The classes compose, and composition is where real incidents live. A delayed event plus a cache TTL produces staleness that outlasts the delay. A replay plus a conflict resolution rule based on arrival order produces a permanently wrong value. A version incompatibility that maps an unknown enum to a default becomes a missing-context failure one hop downstream.

The benchmark set therefore includes composite scenarios, not only single-class injections, since single-class detection is insufficient evidence that a system handles realistic failures.

## What the framework measures

For each scenario, four questions:

| Dimension | Question |
| --- | --- |
| Detection | Does the system notice the invariant violation at all? |
| Localization | Can it identify where information diverged from its expected state? |
| Provenance | Can an engineer reconstruct which inputs, versions, and transformations produced the outcome? |
| Response | Does it apply a safe and visible fallback rather than proceeding silently? |

A system that scores well on detection and poorly on localization is the common and expensive case: the team knows something is wrong and spends days finding out where.

## Open questions

- Which classes are most prevalent in production personalization systems, as against most discussed? The taxonomy is presently derived from practitioner observation and clean-room reproduction, not from a survey.
- Do the detection questions above admit implementation-independent tests, or does each require architecture-specific instrumentation?
- What is the minimum provenance record sufficient for diagnosis, given that full lineage capture is prohibitively expensive at personalization request volumes?

Contributions are welcome, particularly additional failure modes observed in production and reproduction attempts that fail.
