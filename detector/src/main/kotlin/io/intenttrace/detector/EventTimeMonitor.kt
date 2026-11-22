package io.intenttrace.detector

import java.time.Instant

/**
 * Watches the three clocks of an event stream (occurrence, ingestion and
 * decision) and reports two conditions:
 *
 * - **State regression:** an applied event is older, by occurrence time, than
 *   the event already reflected in state. Arrival-order processing has
 *   overwritten newer information (`stale-context`).
 * - **Late arrival:** an event that occurred at or before a recorded decision
 *   arrived after it, so that decision could not reflect it (`delayed-events`).
 *
 * Clocks are assumed to be comparable. Clock skew between producers can make
 * either condition appear or disappear; it is tolerated, never corrected, and
 * never turned into an exception on the host path.
 *
 * A redelivered event is judged by its first ingestion, and each pair of event
 * and decision is reported at most once, while the event is among the
 * [maxTrackedArrivals] most recent arrivals. Memory is bounded by [maxKeys],
 * by [maxDecisionsPerKey] recent decisions per key and by [maxTrackedArrivals].
 */
class EventTimeMonitor(
    private val maxDecisionsPerKey: Int = 64,
    private val maxKeys: Int = 100_000,
    private val maxTrackedArrivals: Int = 100_000
) {
    private class Applied(val eventId: String, val occurredAt: Instant)
    private class RecordedDecision(
        val decisionId: String,
        val decidedAt: Instant,
        val reflectedOccurredAt: Instant?,
        val sequence: Long
    )
    private data class ArrivalKey(val contextKey: String, val eventId: String)

    /** Earliest known ingestion of an event, and the last decision it was already compared with. */
    private class Arrival(val firstIngestedAt: Instant, val comparedThroughSequence: Long)
    private class KeyState(var applied: Applied? = null, val decisions: ArrayDeque<RecordedDecision> = ArrayDeque())

    private val keys = object : LinkedHashMap<String, KeyState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KeyState>?): Boolean = size > maxKeys
    }
    private val arrivals = object : LinkedHashMap<ArrivalKey, Arrival>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ArrivalKey, Arrival>?): Boolean =
            size > maxTrackedArrivals
    }
    private var lastSequence = 0L

    init {
        require(maxDecisionsPerKey > 0) { "At least one decision per key must be retained" }
        require(maxKeys > 0) { "At least one key must be tracked" }
        require(maxTrackedArrivals > 0) { "At least one arrival must be tracked" }
    }

    /** Call after the host applied an event to the state for [contextKey]. */
    @Synchronized
    fun onApplied(contextKey: String, eventId: String, occurredAt: Instant, detectedAt: Instant): Finding? {
        require(contextKey.isNotBlank() && eventId.isNotBlank()) { "Context key and event id must not be blank" }
        val state = keys.getOrPut(contextKey) { KeyState() }
        val previous = state.applied
        state.applied = Applied(eventId, occurredAt)
        if (previous == null || !occurredAt.isBefore(previous.occurredAt)) return null
        return Finding(
            FailureClassId.STALE_CONTEXT, Confidence.CONFIRMED, contextKey, detectedAt,
            "Event $eventId (occurred $occurredAt) replaced newer state from event " +
                "${previous.eventId} (occurred ${previous.occurredAt}).",
            eventId = eventId,
            evidence = mapOf(
                "appliedOccurredAt" to occurredAt.toString(),
                "replacedEventId" to previous.eventId,
                "replacedOccurredAt" to previous.occurredAt.toString()
            )
        )
    }

    /**
     * Call when a decision is made for [contextKey]. [reflectedOccurredAt] is the
     * occurrence time of the newest event the decision reflected, if known.
     */
    @Synchronized
    fun onDecision(contextKey: String, decisionId: String, decidedAt: Instant, reflectedOccurredAt: Instant?) {
        require(contextKey.isNotBlank() && decisionId.isNotBlank()) { "Context key and decision id must not be blank" }
        val decisions = keys.getOrPut(contextKey) { KeyState() }.decisions
        decisions.addLast(RecordedDecision(decisionId, decidedAt, reflectedOccurredAt, ++lastSequence))
        while (decisions.size > maxDecisionsPerKey) decisions.removeFirst()
    }

    /**
     * Call when an event arrives, before or after applying it, including on
     * redelivery. Returns one finding for each retained decision that the
     * event's first ingestion missed and that was not already reported for it.
     */
    @Synchronized
    fun onArrival(contextKey: String, eventId: String, occurredAt: Instant, ingestedAt: Instant,
                  detectedAt: Instant = ingestedAt): List<Finding> {
        require(contextKey.isNotBlank() && eventId.isNotBlank()) { "Context key and event id must not be blank" }
        val arrivalKey = ArrivalKey(contextKey, eventId)
        val earlier = arrivals[arrivalKey]
        val firstIngestedAt = earlier?.firstIngestedAt?.let { minOf(it, ingestedAt) } ?: ingestedAt
        val comparedThrough = earlier?.comparedThroughSequence ?: 0L
        arrivals.remove(arrivalKey)
        arrivals[arrivalKey] = Arrival(firstIngestedAt, lastSequence)
        val decisions = keys[contextKey]?.decisions ?: return emptyList()
        return decisions
            .filter { it.sequence > comparedThrough }
            .filter { !occurredAt.isAfter(it.decidedAt) && firstIngestedAt.isAfter(it.decidedAt) }
            .filter { it.reflectedOccurredAt == null || occurredAt.isAfter(it.reflectedOccurredAt) }
            .map { decision ->
                Finding(
                    FailureClassId.DELAYED_EVENTS, Confidence.CONFIRMED, contextKey, detectedAt,
                    "Event $eventId occurred before decision ${decision.decisionId} but arrived after it.",
                    decisionId = decision.decisionId, eventId = eventId,
                    evidence = mapOf(
                        "occurredAt" to occurredAt.toString(),
                        "ingestedAt" to firstIngestedAt.toString(),
                        "decidedAt" to decision.decidedAt.toString(),
                        "reflectedOccurredAt" to (decision.reflectedOccurredAt?.toString() ?: "unknown")
                    )
                )
            }
    }

    /** Number of arrivals currently remembered for redelivery handling. */
    @get:Synchronized
    val trackedArrivals: Int
        get() = arrivals.size
}
