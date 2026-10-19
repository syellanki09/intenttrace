package io.intenttrace.pipeline.context

import io.intenttrace.pipeline.model.Context
import java.time.Instant

/**
 * Stores versioned context and exposes the latest state that was eligible
 * at a specific decision boundary.
 */
interface ContextStore {
    fun save(context: Context)

    fun find(
        contextId: String,
        version: Long
    ): Context?

    fun latestEligible(
        contextId: String,
        decidedAt: Instant
    ): Context?
}
