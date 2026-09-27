package io.intenttrace.pipeline.context

import io.intenttrace.pipeline.model.Context
import java.time.Instant

/**
 * Selects the context used by a decision.
 *
 * Keeping resolution separate from storage allows benchmark scenarios to
 * inject stale reads without altering the underlying context history.
 */
fun interface ContextResolver {

    fun resolve(
        contextId: String,
        decidedAt: Instant
    ): Context?
}

/**
 * Normal resolution policy: use the most recently committed context that was
 * eligible at the decision boundary.
 */
class LatestEligibleContextResolver(
    private val contextStore: ContextStore
) : ContextResolver {

    override fun resolve(
        contextId: String,
        decidedAt: Instant
    ): Context? =
        contextStore.latestEligible(
            contextId = contextId,
            decidedAt = decidedAt
        )
}