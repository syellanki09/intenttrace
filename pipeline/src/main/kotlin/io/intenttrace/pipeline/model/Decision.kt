package io.intenttrace.pipeline.model

import java.time.Instant

/**
 * Explains why a decision proceeded with degraded information.
 */
enum class DegradationReason {
    MISSING_PREFERRED_CATEGORY,
    NO_ELIGIBLE_CONTEXT,
    CONTEXT_UNAVAILABLE
}

/**
 * Result produced by the reference decision pipeline.
 *
 * Context versions are nullable because a valid degraded decision may exist
 * before any context is eligible, or when eligible context exists but does
 * not reach the decision path.
 *
 * Freshness ground truth is deliberately separate from detection. A benchmark
 * may know that stale context was used even when the system under test emitted
 * no signal indicating that it noticed the violation.
 */
data class Decision(
    val id: String,
    val contextId: String,
    val usedContextVersion: Long?,
    val latestEligibleContextVersion: Long?,
    val decidedAt: Instant,
    val outcome: String,
    val degradationReason: DegradationReason? = null
) {

    init {
        require(id.isNotBlank()) {
            "Decision id must not be blank"
        }

        require(contextId.isNotBlank()) {
            "Decision context id must not be blank"
        }

        usedContextVersion?.let {
            require(it > 0) {
                "Used context version must be greater than zero"
            }
        }

        latestEligibleContextVersion?.let {
            require(it > 0) {
                "Latest eligible context version must be greater than zero"
            }
        }

        require(
            usedContextVersion == null ||
                    latestEligibleContextVersion != null
        ) {
            "A used context requires an eligible context boundary"
        }

        when (degradationReason) {
            DegradationReason.NO_ELIGIBLE_CONTEXT -> {
                require(
                    usedContextVersion == null &&
                            latestEligibleContextVersion == null
                ) {
                    "NO_ELIGIBLE_CONTEXT cannot carry context versions"
                }
            }

            DegradationReason.CONTEXT_UNAVAILABLE -> {
                require(
                    usedContextVersion == null &&
                            latestEligibleContextVersion != null
                ) {
                    "CONTEXT_UNAVAILABLE requires eligible context " +
                            "but no used context"
                }
            }

            DegradationReason.MISSING_PREFERRED_CATEGORY -> {
                require(
                    usedContextVersion != null &&
                            latestEligibleContextVersion != null
                ) {
                    "MISSING_PREFERRED_CATEGORY requires a used context"
                }
            }

            null -> {
                require(
                    usedContextVersion != null &&
                            latestEligibleContextVersion != null
                ) {
                    "A normal decision requires concrete context"
                }
            }
        }
    }

    val degraded: Boolean
        get() = degradationReason != null

    /**
     * Benchmark ground truth for the stale-context invariant.
     *
     * This says stale context was used. It does not say that the system under
     * test detected or reported the violation.
     */
    val freshnessViolated: Boolean
        get() =
            usedContextVersion != null &&
                    latestEligibleContextVersion != null &&
                    usedContextVersion != latestEligibleContextVersion
}
