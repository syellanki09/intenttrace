package io.intenttrace.evaluation

import java.time.Instant

data class TimedEvent(val eventId: String, val occurredAt: Instant, val ingestedAt: Instant, val value: String) {
    init { require(eventId.isNotBlank() && value.isNotBlank() && ingestedAt >= occurredAt) }
}

data class EventTimeInvariantResult(
    val expected: TimedEvent?,
    val observed: TimedEvent?,
    val status: InvariantStatus,
    val detected: Boolean?,
    val failureClass: FailureClass
)

/** Harness event history defines ground truth independently of arrival order. */
class EventTimeInvariant {
    fun evaluate(events: List<TimedEvent>, decidedAt: Instant, observed: TimedEvent?,
                 detected: Boolean? = null): EventTimeInvariantResult {
        require(events.map { it.eventId }.distinct().size == events.size) { "Event identities must be unique" }
        require(events.map { it.occurredAt }.distinct().size == events.size) {
            "This invariant requires unambiguous event-time ordering"
        }
        require(observed == null || observed in events) { "Observed event must belong to harness history" }
        require(observed == null || observed.ingestedAt <= decidedAt) { "Observed event must have arrived by the decision" }
        val expected = events.filter { it.occurredAt <= decidedAt }.maxByOrNull { it.occurredAt }
        val status = when {
            expected == null -> InvariantStatus.NOT_APPLICABLE
            expected == observed -> InvariantStatus.SATISFIED
            else -> InvariantStatus.VIOLATED
        }
        val failureClass = if (expected != null && expected.ingestedAt > decidedAt) FailureClass.DELAYED_EVENTS
            else FailureClass.STALE_CONTEXT
        return EventTimeInvariantResult(expected, observed, status, detected, failureClass)
    }
}
