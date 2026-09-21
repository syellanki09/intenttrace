package io.intenttrace.pipeline

import io.intenttrace.pipeline.model.Context
import io.intenttrace.pipeline.model.Decision
import java.time.Instant

/**
 * Minimal deterministic reference decision pipeline.
 *
 * The pipeline intentionally contains no infrastructure dependencies. Given a
 * context and explicit decision metadata, it produces the same decision for
 * the same inputs.
 */
class ReferencePipeline {

    fun decide(
        decisionId: String,
        context: Context,
        decidedAt: Instant
    ): Decision {
        require(decisionId.isNotBlank()) {
            "Decision id must not be blank"
        }

        require(!decidedAt.isBefore(context.committedAt)) {
            "Decision cannot precede the context commit"
        }

        val outcome = context.attributes["preferredCategory"] ?: "default"

        return Decision(
            id = decisionId,
            contextId = context.id,
            contextVersion = context.version,
            decidedAt = decidedAt,
            outcome = outcome
        )
    }
}