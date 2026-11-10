package io.intenttrace.evaluation

data class ReplayEvent(val eventId: String, val points: Long) {
    init { require(eventId.isNotBlank() && points > 0) }
}

data class ReplayInvariantResult(
    val expectedTotal: Long,
    val observedTotal: Long,
    val status: InvariantStatus,
    val detected: Boolean?
) {
    val failureClass: FailureClass get() = FailureClass.DUPLICATE_REPLAY
}

/** Each logical event must contribute exactly once, regardless of delivery count. */
class ReplayInvariant {
    fun evaluate(
        deliveries: List<ReplayEvent>,
        appliedEventIds: List<String>,
        observedTotal: Long,
        detected: Boolean? = null
    ): ReplayInvariantResult {
        require(deliveries.isNotEmpty()) { "Replay evaluation requires delivery evidence" }
        val unique = deliveries.groupBy { it.eventId }
        require(unique.values.all { group -> group.map { it.points }.distinct().size == 1 }) {
            "Conflicting payloads for one identity are not duplicate-replay evidence"
        }
        val deliveredCounts = deliveries.groupingBy { it.eventId }.eachCount()
        val appliedCounts = appliedEventIds.groupingBy { it }.eachCount()
        require(appliedCounts.all { (id, count) -> count <= (deliveredCounts[id] ?: 0) }) {
            "Applied events must correspond to actual deliveries"
        }
        val expected = unique.values.fold(0L) { total, group -> Math.addExact(total, group.first().points) }
        val satisfied = observedTotal == expected && unique.keys.all { appliedCounts[it] == 1 }
        return ReplayInvariantResult(expected, observedTotal,
            if (satisfied) InvariantStatus.SATISFIED else InvariantStatus.VIOLATED, detected)
    }
}
