package io.intenttrace.pipeline

import io.intenttrace.pipeline.context.ContextResolver
import io.intenttrace.pipeline.context.ContextStore
import io.intenttrace.pipeline.context.LatestEligibleContextResolver
import io.intenttrace.pipeline.model.Decision
import io.intenttrace.pipeline.model.DegradationReason
import java.time.Instant

/**
 * Minimal deterministic reference decision pipeline.
 *
 * Context storage and resolution are deliberately separate. The store records
 * what context existed at the decision boundary, while the resolver determines
 * what context the decision path actually receives.
 */
class ReferencePipeline(
    private val contextStore: ContextStore,
    private val contextResolver: ContextResolver =
        LatestEligibleContextResolver(contextStore)
) {

    fun decide(
        decisionId: String,
        contextId: String,
        decidedAt: Instant
    ): Decision {

        require(decisionId.isNotBlank()) {
            "Decision id must not be blank"
        }

        require(contextId.isNotBlank()) {
            "Context id must not be blank"
        }

        val latestEligible =
            contextStore.latestEligible(
                contextId = contextId,
                decidedAt = decidedAt
            )

        if (latestEligible == null) {
            return Decision(
                id = decisionId,
                contextId = contextId,
                usedContextVersion = null,
                latestEligibleContextVersion = null,
                decidedAt = decidedAt,
                outcome = "default",
                degradationReason =
                    DegradationReason.NO_ELIGIBLE_CONTEXT
            )
        }

        val usedContext =
            contextResolver.resolve(
                contextId = contextId,
                decidedAt = decidedAt
            )

        if (usedContext == null) {
            return Decision(
                id = decisionId,
                contextId = contextId,
                usedContextVersion = null,
                latestEligibleContextVersion =
                    latestEligible.version,
                decidedAt = decidedAt,
                outcome = "default",
                degradationReason =
                    DegradationReason.CONTEXT_UNAVAILABLE
            )
        }

        require(usedContext.id == contextId) {
            "Resolved context id ${usedContext.id} " +
                    "does not match requested context id $contextId"
        }

        require(!decidedAt.isBefore(usedContext.committedAt)) {
            "Decision cannot precede the used context commit"
        }

        require(
            contextStore.find(
                contextId = contextId,
                version = usedContext.version
            ) == usedContext
        ) {
            "Resolved context must belong to the context history"
        }

        val preferredCategory =
            usedContext.attributes["preferredCategory"]

        return Decision(
            id = decisionId,
            contextId = contextId,
            usedContextVersion = usedContext.version,
            latestEligibleContextVersion =
                latestEligible.version,
            decidedAt = decidedAt,
            outcome = preferredCategory ?: "default",
            degradationReason =
                if (preferredCategory == null) {
                    DegradationReason.MISSING_PREFERRED_CATEGORY
                } else {
                    null
                }
        )
    }
}
