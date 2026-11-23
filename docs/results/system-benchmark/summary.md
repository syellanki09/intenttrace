# System benchmark summary

Synthetic workload on one machine, Kafka and Redis in Docker. Not production validation.

- Source commit: `eef5991d231f47c317e49a8ce8e0e7a4d4742c02`
- Repetitions per mode: 3; seed 42
- Workload: 2000 events/s and 2000 decisions/s over 10000 users; 15 s warmup, 30 s measured, 10 s saturation
- Host: 4 processors, Java 17.0.21.1

## Overhead

| Metric | Detector off | Inline checks | Off-path checks |
| --- | --- | --- | --- |
| Decision latency p50 | 188 µs | 379 µs (+102%) | 198 µs (+5%) |
| Decision latency p99 | 1.62 ms | 2.95 ms (+82%) | 2.24 ms (+39%) |
| Decision latency p99.9 | 4.76 ms | 24.92 ms (+424%) | 22.86 ms (+380%) |
| Decision service time p50 | 176 µs | 360 µs (+104%) | 185 µs (+5%) |
| Decision service time p99 | 909 µs | 1.46 ms (+61%) | 918 µs (+1%) |
| Decision CPU per decision | 77.7 µs | 110 µs (+41%) | 122 µs (+57%) |
| Allocation per decision | 3,211 bytes | 5,008 bytes (+56%) | 5,065 bytes (+58%) |
| Saturation throughput | 20,487 decisions/s | 10,214 decisions/s (-50%) | 18,835 decisions/s (-8%) |
| Oracle call p50 | n/a | 155 µs | 136 µs |
| Oracle call p99 | n/a | 675 µs | 585 µs |
| Consumer CPU per event | 67.5 µs | 76.6 µs (+13%) | 73.7 µs (+9%) |
| Allocation per event | 13,458 bytes | 15,210 bytes (+13%) | 15,196 bytes (+13%) |
| Event propagation p50 | 1.93 ms | 2.02 ms (+5%) | 1.97 ms (+2%) |
| Event propagation p99 | 87.03 ms | 41.07 ms (-53%) | 38.34 ms (-56%) |
| Heap used after GC | 17.7 MiB | 83.9 MiB (+374%) | 88.1 MiB (+398%) |

Medians across repetitions, with the change from detector off in brackets. Min and max are in summary.json.

## Detection: inline checks (pooled across repetitions)

| Condition | Ground truth | Found | Precision | Recall | Recall at fixed rate | Recall at saturation |
| --- | --- | --- | --- | --- | --- | --- |
| Reused event id, different payload | 300 | 300 | 1.0000 | 1.0000 |  |  |
| Event arrived after a decision that needed it | 26,693 | 26,373 | 1.0000 | 0.9880 | 0.9989 | 0.9778 |
| Duplicate delivery | 3,267 | 3,267 | 1.0000 | 1.0000 |  |  |
| Missing context at decision | 13,190 | 13,190 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |
| Stale read at decision | 26,652 | 26,500 | 1.0000 | 0.9943 | 0.9953 | 0.9935 |
| Older event replaced newer state | 1,105 | 1,105 | 1.0000 | 1.0000 |  |  |

## Detection: off-path checks (pooled across repetitions)

| Condition | Ground truth | Found | Precision | Recall | Recall at fixed rate | Recall at saturation |
| --- | --- | --- | --- | --- | --- | --- |
| Reused event id, different payload | 300 | 300 | 1.0000 | 1.0000 |  |  |
| Event arrived after a decision that needed it | 39,196 | 10,006 | 1.0000 | 0.2553 | 0.8518 | 0.0005 |
| Duplicate delivery | 3,267 | 3,267 | 1.0000 | 1.0000 |  |  |
| Missing context at decision | 19,460 | 11,475 | 1.0000 | 0.5897 | 1.0000 | 0.4021 |
| Stale read at decision | 38,757 | 16,265 | 1.0000 | 0.4197 | 0.9992 | 0.1626 |
| Older event replaced newer state | 1,105 | 1,105 | 1.0000 | 1.0000 |  |  |

Ground truth with the detector off, for comparison: Reused event id, different payload 300; Event arrived after a decision that needed it 36,036; Duplicate delivery 3,267; Missing context at decision 20,374; Stale read at decision 41,014; Older event replaced newer state 2,106.

Check failures 0, dropped checks 278213, sink failures 0, send failures 0, evaluator anomalies 0.
