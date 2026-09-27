package io.intenttrace.pipeline.model

import java.time.Instant

/**
 * Explains why a decision had to proceed with degraded information.
 */
enum class DegradationReason {
    MISSING_PREFERRED_CATEGORY
}

/**
 * Result produced by the reference decision pipeline.
 *
 * A decision records both the context version actually used and the latest
 * version eligible at the decision boundary. Keeping both values makes
 * freshness violations observable without inferring them from latency or
 * availability signals.
 */
data class Decision(
    val id: String,
    val contextId: String,
    val usedContextVersion: Long,
    val latestEligibleContextVersion: Long,
    val decidedAt: Instant,
    val outcome: String,
    val degraded: Boolean,
    val degradationReason: DegradationReason? = null
) {

    init {
        require(id.isNotBlank()) {
            "Decision id must not be blank"
        }

        require(contextId.isNotBlank()) {
            "Decision context id must not be blank"
        }

        require(usedContextVersion > 0) {
            "Used context version must be greater than zero"
        }

        require(latestEligibleContextVersion > 0) {
            "Latest eligible context version must be greater than zero"
        }

        require(degraded == (degradationReason != null)) {
            "Degraded decisions must include a degradation reason, " +
                    "and normal decisions must not"
        }
    }
}