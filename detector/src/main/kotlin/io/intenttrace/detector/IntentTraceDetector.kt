package io.intenttrace.detector

import java.time.Clock
import java.time.Instant
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime entry point for host systems.
 *
 * Each call evaluates one check and forwards every finding to [sinks]. Neither
 * a sink that throws nor a check that fails (for example an oracle whose
 * database is unreachable) breaks the host's path: the failure is counted in
 * [sinkFailures] or [checkFailures] and the call reports no findings. Calling
 * a check that is not configured is a programming error and does throw.
 *
 * ```kotlin
 * val detector = IntentTraceDetector(
 *     sinks = listOf(OpenTelemetryFindingSink(openTelemetry)), // from the detector-otel module
 *     freshness = FreshnessCheck(oracle = { key, at -> primary.latestVersion(key, at) }),
 *     replay = ReplayGuard(retention = Duration.ofHours(1)),
 *     eventTime = EventTimeMonitor()
 * )
 * detector.checkDecision(ContextRead(decisionId, userId, now, VersionStamp(read.version, read.committedAt)))
 * ```
 */
class IntentTraceDetector(
    private val sinks: List<FindingSink>,
    private val freshness: FreshnessCheck? = null,
    private val replay: ReplayGuard? = null,
    private val eventTime: EventTimeMonitor? = null,
    private val clock: Clock = Clock.systemUTC()
) {
    private val counts = EnumMap<FailureClassId, AtomicLong>(FailureClassId::class.java).apply {
        FailureClassId.entries.forEach { put(it, AtomicLong()) }
    }
    private val failedDeliveries = AtomicLong()
    private val failedChecks = AtomicLong()

    init {
        require(freshness != null || replay != null || eventTime != null) { "Configure at least one check" }
    }

    /**
     * Checks one decision's context read and, when event-time monitoring is on,
     * records the decision boundary so later late arrivals can be attributed to it.
     */
    fun checkDecision(read: ContextRead, reflectedOccurredAt: Instant? = null): List<Finding> {
        guarded(Unit) { eventTime?.onDecision(read.contextKey, read.decisionId, read.decidedAt, reflectedOccurredAt) }
        return publish(guarded(emptyList()) { freshness?.check(read, clock.instant()).orEmpty() })
    }

    /** Records an event delivery; follow [DeliveryResult.shouldApply] for idempotent processing. */
    fun onDelivery(contextKey: String, eventId: String, payloadFingerprint: String,
                   deliveredAt: Instant = clock.instant()): DeliveryResult {
        val guard = checkNotNull(replay) { "Replay detection is not configured" }
        val result = guarded(DeliveryResult(DeliveryVerdict.APPLY, null)) {
            guard.onDelivery(contextKey, eventId, payloadFingerprint, deliveredAt, clock.instant())
        }
        result.finding?.let { publish(listOf(it)) }
        return result
    }

    /** Records an event arrival with its occurrence time. */
    fun onArrival(contextKey: String, eventId: String, occurredAt: Instant,
                  ingestedAt: Instant = clock.instant()): List<Finding> {
        val monitor = checkNotNull(eventTime) { "Event-time monitoring is not configured" }
        return publish(guarded(emptyList()) { monitor.onArrival(contextKey, eventId, occurredAt, ingestedAt, clock.instant()) })
    }

    /** Records that an event was applied to state. */
    fun onApplied(contextKey: String, eventId: String, occurredAt: Instant): Finding? {
        val monitor = checkNotNull(eventTime) { "Event-time monitoring is not configured" }
        return publish(guarded(emptyList()) { listOfNotNull(monitor.onApplied(contextKey, eventId, occurredAt, clock.instant())) })
            .singleOrNull()
    }

    /** Findings published so far, by failure class. */
    fun findingCounts(): Map<FailureClassId, Long> = counts.mapValues { it.value.get() }

    val sinkFailures: Long
        get() = failedDeliveries.get()

    val checkFailures: Long
        get() = failedChecks.get()

    private inline fun <T> guarded(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (_: Exception) {
            failedChecks.incrementAndGet()
            fallback
        }

    private fun publish(findings: List<Finding>): List<Finding> {
        findings.forEach { finding ->
            counts.getValue(finding.failureClass).incrementAndGet()
            sinks.forEach { sink ->
                try {
                    sink.accept(finding)
                } catch (_: Exception) {
                    failedDeliveries.incrementAndGet()
                }
            }
        }
        return findings
    }
}
