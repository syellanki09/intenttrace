package io.intenttrace.evaluation

import io.intenttrace.pipeline.model.Decision

/** Independently evaluates recorded versions without using Decision.freshnessViolated. */
class FreshnessInvariant {
    fun evaluate(decision: Decision, detected: Boolean? = null): InvariantResult {
        val expected = decision.latestEligibleContextVersion
        val observed = decision.usedContextVersion
        val status = when {
            expected == null || observed == null -> InvariantStatus.NOT_APPLICABLE
            expected == observed -> InvariantStatus.SATISFIED
            else -> InvariantStatus.VIOLATED
        }
        val explanation = when (status) {
            InvariantStatus.NOT_APPLICABLE ->
                "Freshness cannot be evaluated without both used and latest eligible context versions."
            InvariantStatus.SATISFIED ->
                "Decision used the latest eligible context version $expected."
            InvariantStatus.VIOLATED ->
                "Decision used context version $observed instead of latest eligible version $expected."
        }
        return InvariantResult(
            failureClass = FailureClass.STALE_CONTEXT,
            status = status,
            expectedVersion = expected,
            observedVersion = observed,
            detected = detected,
            explanation = explanation
        )
    }
}
