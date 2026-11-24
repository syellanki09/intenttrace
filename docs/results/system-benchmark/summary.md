# System benchmark summary

Synthetic workload on one machine, Kafka and Redis in Docker. Not production validation.

- Source commit: `3004ff045aa2eea411a51b5dd73aab5d38bcdbba`
- Repetitions per mode: 3; seed 42
- Workload: 2000 events/s and 2000 decisions/s over 10000 users; 15 s warmup, 30 s measured, 10 s saturation
- Host: 4 processors, Java 17.0.21.1

## Overhead

| Metric | Detector off | Inline checks | Off-path checks |
| --- | --- | --- | --- |
| Decision latency p50 | 198 µs | 432 µs (+118%) | 200 µs (+1%) |
| Decision latency p99 | 1.74 ms | 4.51 ms (+159%) | 2.18 ms (+25%) |
| Decision latency p99.9 | 7.51 ms | 24.14 ms (+221%) | 15.88 ms (+111%) |
| Decision service time p50 | 185 µs | 403 µs (+118%) | 187 µs (+1%) |
| Decision service time p99 | 943 µs | 1.94 ms (+106%) | 1.03 ms (+9%) |
| Decision CPU per decision | 81.2 µs | 116 µs (+43%) | 123 µs (+52%) |
| Allocation per decision | 3,213 bytes | 4,986 bytes (+55%) | 5,047 bytes (+57%) |
| Saturation throughput | 21,533 decisions/s | 7,716 decisions/s (-64%) | 19,123 decisions/s (-11%) |
| Oracle call p50 | n/a | 172 µs | 138 µs |
| Oracle call p99 | n/a | 919 µs | 636 µs |
| Consumer CPU per event | 69.4 µs | 80.4 µs (+16%) | 72.5 µs (+4%) |
| Allocation per event | 13,582 bytes | 15,255 bytes (+12%) | 15,245 bytes (+12%) |
| Event propagation p50 | 2.04 ms | 2.21 ms (+8%) | 1.99 ms (-2%) |
| Event propagation p99 | 86.27 ms | 48.73 ms (-44%) | 34.31 ms (-60%) |
| Heap used after GC | 17.7 MiB | 82.3 MiB (+365%) | 87.9 MiB (+397%) |

Medians across repetitions, with the change from detector off in brackets. Min and max are in summary.json.

## Detection: inline checks (pooled across repetitions)

| Condition | Ground truth | Found | Precision | Recall | Recall at fixed rate | Recall at saturation |
| --- | --- | --- | --- | --- | --- | --- |
| Reused event id, different payload | 300 | 300 | 1.0000 | 1.0000 |  |  |
| Event arrived after a decision that needed it | 32,597 | 32,350 | 1.0000 | 0.9924 | 0.9988 | 0.9862 |
| Duplicate delivery | 3,267 | 3,267 | 1.0000 | 1.0000 |  |  |
| Missing context at decision | 11,952 | 11,952 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| Stale read at decision | 27,199 | 27,199 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| Older event replaced newer state | 1,108 | 1,108 | 1.0000 | 1.0000 |  |  |

## Detection: off-path checks (pooled across repetitions)

| Condition | Ground truth | Found | Precision | Recall | Recall at fixed rate | Recall at saturation |
| --- | --- | --- | --- | --- | --- | --- |
| Reused event id, different payload | 300 | 300 | 1.0000 | 1.0000 |  |  |
| Event arrived after a decision that needed it | 45,973 | 14,256 | 1.0000 | 0.3101 | 0.8115 | 0.0004 |
| Duplicate delivery | 3,267 | 3,267 | 1.0000 | 1.0000 |  |  |
| Missing context at decision | 19,454 | 11,523 | 1.0000 | 0.5923 | 1.0000 | 0.4051 |
| Stale read at decision | 40,372 | 18,414 | 1.0000 | 0.4561 | 1.0000 | 0.1689 |
| Older event replaced newer state | 1,108 | 1,108 | 1.0000 | 1.0000 |  |  |

Ground truth with the detector off, for comparison: Reused event id, different payload 300; Event arrived after a decision that needed it 42,217; Duplicate delivery 3,267; Missing context at decision 19,183; Stale read at decision 41,418; Older event replaced newer state 2,108.

Check failures 0, dropped checks 283553, sink failures 0, send failures 0, evaluator anomalies 0.
