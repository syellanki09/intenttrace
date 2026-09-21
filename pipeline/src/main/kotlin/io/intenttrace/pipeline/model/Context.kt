package io.intenttrace.pipeline.model

import java.time.Instant

/**
 * Immutable contextual state available to the decision pipeline.
 *
 * A logical context may have multiple versions over time. The version and
 * commit timestamp allow the pipeline and future benchmark scenarios to
 * distinguish the state used by a decision from newer eligible state.
 */
data class Context(
    val id: String,
    val version: Long,
    val committedAt: Instant,
    val attributes: Map<String, String>
) {
    init {
        require(id.isNotBlank()) {
            "Context id must not be blank"
        }

        require(version > 0) {
            "Context version must be greater than zero"
        }
    }
}