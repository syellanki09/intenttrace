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
 * what context the decision actually receives.
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
                ?: error(
                    "No eligible context found for $contextId at $decidedAt"
                )

        val usedContext =
            contextResolver.resolve(
                contextId = contextId,
                decidedAt = decidedAt
            )
                ?: error(
                    "Context resolver returned no context " +
                            "for $contextId at $decidedAt"
                )

        require(usedContext.id == contextId) {
            "Resolved context id ${usedContext.id} " +
                    "does not match requested context id $contextId"
        }

        require(!decidedAt.isBefore(usedContext.committedAt)) {
            "Decision cannot precede the used context commit"
        }

        val preferredCategory =
            usedContext.attributes["preferredCategory"]

        val degraded = preferredCategory == null

        return Decision(
            id = decisionId,
            contextId = contextId,
            usedContextVersion = usedContext.version,
            latestEligibleContextVersion = latestEligible.version,
            decidedAt = decidedAt,
            outcome = preferredCategory ?: "default",
            degraded = degraded,
            degradationReason =
                if (degraded) {
                    DegradationReason.MISSING_PREFERRED_CATEGORY
                } else {
                    null
                }
        )
    }
}
