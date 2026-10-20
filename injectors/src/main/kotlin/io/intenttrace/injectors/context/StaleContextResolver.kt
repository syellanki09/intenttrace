package io.intenttrace.injectors.context

import io.intenttrace.pipeline.context.ContextResolver
import io.intenttrace.pipeline.context.ContextStore
import io.intenttrace.pipeline.model.Context
import java.time.Instant

/**
 * Deterministically forces a decision to use an older eligible context.
 *
 * The injector never modifies the underlying context history. ContextStore
 * remains the source of benchmark ground truth while this resolver controls
 * which eligible version reaches the decision path.
 */
class StaleContextResolver(
    private val contextStore: ContextStore,
    private val pinnedVersion: Long
) : ContextResolver {
    init {
        require(pinnedVersion > 0) {
            "Pinned context version must be greater than zero"
        }
    }

    override fun resolve(
        contextId: String,
        decidedAt: Instant
    ): Context {
        require(contextId.isNotBlank()) {
            "Context id must not be blank"
        }

        val latestEligible = requireNotNull(
            contextStore.latestEligible(
                contextId = contextId,
                decidedAt = decidedAt
            )
        ) {
            "Cannot inject stale context when no context " +
                "is eligible for $contextId at $decidedAt"
        }

        val pinnedContext = requireNotNull(
            contextStore.find(
                contextId = contextId,
                version = pinnedVersion
            )
        ) {
            "Pinned context version $pinnedVersion " +
                "does not exist for $contextId"
        }

        require(!pinnedContext.committedAt.isAfter(decidedAt)) {
            "Pinned context version $pinnedVersion " +
                "was not eligible at decision time"
        }

        require(pinnedContext.version < latestEligible.version) {
            "Pinned context version ${pinnedContext.version} " +
                "is not stale relative to latest eligible version " +
                "${latestEligible.version}"
        }

        return pinnedContext
    }
}
