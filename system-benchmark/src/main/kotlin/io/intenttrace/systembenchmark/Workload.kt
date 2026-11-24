package io.intenttrace.systembenchmark

import java.util.SplittableRandom

/** Draws user indexes with a Zipf distribution, so a few users are hot. */
class ZipfSampler(n: Int, exponent: Double) {
    private val cumulative = DoubleArray(n)

    init {
        require(n > 0) { "At least one user is needed" }
        require(exponent >= 0.0) { "Zipf exponent must not be negative" }
        var total = 0.0
        for (rank in 1..n) {
            total += 1.0 / Math.pow(rank.toDouble(), exponent)
            cumulative[rank - 1] = total
        }
        for (i in cumulative.indices) cumulative[i] /= total
        cumulative[n - 1] = 1.0
    }

    fun sample(random: SplittableRandom): Int {
        val u = random.nextDouble()
        var low = 0
        var high = cumulative.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cumulative[mid] < u) low = mid + 1 else high = mid
        }
        return low
    }
}

/** How the synthetic producer misbehaves for one event. */
enum class SendFault(val id: String) {
    NONE("none"),

    /** The event is sent late, so newer events for the same user can overtake it. */
    DELAYED("delayed"),

    /** The event is sent again later with the same payload, as after a retry that was not needed. */
    DUPLICATE("duplicate"),

    /** The event identifier is reused later for a different payload. */
    CONFLICTING("conflicting")
}

/** One context update: committed to the primary at [atNanos] after the start of the run. */
data class ScheduledEvent(val index: Int, val atNanos: Long, val user: Int, val fault: SendFault)

/** One decision request for [user], due [atNanos] after the start of the run. */
data class ScheduledDecision(val index: Int, val atNanos: Long, val user: Int)

/** One deletion of a user's cache entry, due [atNanos] after the start of the run. */
data class ScheduledEviction(val atNanos: Long, val user: Int)

/**
 * The whole synthetic workload for one run, generated up front from the seed.
 * Arrival times follow a Poisson process at the configured rates.
 */
class Workload(
    val events: List<ScheduledEvent>,
    val decisions: List<ScheduledDecision>,
    val evictions: List<ScheduledEviction>
) {
    companion object {
        fun generate(config: BenchmarkConfig): Workload {
            val zipf = ZipfSampler(config.users, config.zipfExponent)
            val eventRandom = SplittableRandom(config.seed)
            val decisionRandom = SplittableRandom(config.seed * 31 + 1)
            val evictionRandom = SplittableRandom(config.seed * 31 + 2)
            val totalNanos = config.totalSeconds * 1_000_000_000L

            val events = ArrayList<ScheduledEvent>()
            poisson(eventRandom, config.eventsPerSecond, totalNanos) { at ->
                val user = zipf.sample(eventRandom)
                val roll = eventRandom.nextDouble()
                val fault = when {
                    roll < config.delayedSendRate -> SendFault.DELAYED
                    roll < config.delayedSendRate + config.duplicateRate -> SendFault.DUPLICATE
                    roll < config.delayedSendRate + config.duplicateRate + config.conflictingRate -> SendFault.CONFLICTING
                    else -> SendFault.NONE
                }
                events += ScheduledEvent(events.size, at, user, fault)
            }

            val decisions = ArrayList<ScheduledDecision>()
            poisson(decisionRandom, config.decisionsPerSecond, config.fixedRateSeconds * 1_000_000_000L) { at ->
                decisions += ScheduledDecision(decisions.size, at, zipf.sample(decisionRandom))
            }

            val evictions = ArrayList<ScheduledEviction>()
            if (config.evictionsPerSecond > 0) {
                poisson(evictionRandom, config.evictionsPerSecond, totalNanos) { at ->
                    evictions += ScheduledEviction(at, zipf.sample(evictionRandom))
                }
            }
            return Workload(events, decisions, evictions)
        }

        private inline fun poisson(random: SplittableRandom, perSecond: Int, untilNanos: Long, emit: (Long) -> Unit) {
            val meanGap = 1_000_000_000.0 / perSecond
            var at = 0.0
            while (true) {
                at += -Math.log(1.0 - random.nextDouble()) * meanGap
                if (at >= untilNanos) return
                emit(at.toLong())
            }
        }
    }
}

/** Whether the consumer silently fails to write this event to the cache, as a lost cache update. */
fun isLostWrite(eventId: String, perMille: Int): Boolean = Math.floorMod(eventId.hashCode() * 0x9E3779B1.toInt(), 1000) < perMille
