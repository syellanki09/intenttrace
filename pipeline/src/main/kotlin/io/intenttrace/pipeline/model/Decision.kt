package io.intenttrace.pipeline.model

import java.time.Instant

/**
 * Result produced by the reference decision pipeline.
 *
 * The context identity and version are retained so a decision can be traced
 * back to the exact contextual state that produced it.
 */
data class Decision(
    val id: String,
    val contextId: String,
    val contextVersion: Long,
    val decidedAt: Instant,
    val outcome: String
) {
    init {
        require(id.isNotBlank()) {
            "Decision id must not be blank"
        }

        require(contextId.isNotBlank()) {
            "Decision context id must not be blank"
        }

        require(contextVersion > 0) {
            "Decision context version must be greater than zero"
        }
    }
}