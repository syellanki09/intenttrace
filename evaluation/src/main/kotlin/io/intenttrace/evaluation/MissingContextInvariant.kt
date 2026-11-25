package io.intenttrace.evaluation

/** One committed version of a context value. */
data class ContextSnapshot(val version: Long, val value: String) {
    init { require(version > 0 && value.isNotBlank()) }
}

/** A host's record that a decision went ahead without its required context, and which source failed. */
data class Degradation(val reason: String, val source: String) {
    init { require(reason.isNotBlank() && source.isNotBlank()) }
}

data class MissingContextInvariantResult(
    val required: ContextSnapshot?,
    val delivered: ContextSnapshot?,
    val usedValue: String,
    val status: InvariantStatus,
    val detected: Boolean?
) {
    val failureClass: FailureClass get() = FailureClass.MISSING_CONTEXT
}

/**
 * When required context is unavailable to a decision, the decision either fails
 * visibly or degrades in a way that is recorded and attributable. A default
 * value used without such a record violates the invariant.
 */
class MissingContextInvariant {
    fun evaluate(
        required: ContextSnapshot?,
        delivered: ContextSnapshot?,
        usedValue: String,
        degradation: Degradation?,
        detected: Boolean? = null
    ): MissingContextInvariantResult {
        require(usedValue.isNotBlank()) { "A decision must record the value it used" }
        require(delivered == null || delivered == required) {
            "A delivered snapshot that differs from the required one is stale-context evidence, not missing-context evidence"
        }
        require(delivered == null || degradation == null) { "A degradation record requires a missing read" }
        val status = when {
            required == null -> InvariantStatus.NOT_APPLICABLE
            delivered != null -> if (usedValue == delivered.value) InvariantStatus.SATISFIED else InvariantStatus.VIOLATED
            degradation != null -> InvariantStatus.SATISFIED
            else -> InvariantStatus.VIOLATED
        }
        return MissingContextInvariantResult(required, delivered, usedValue, status, detected)
    }
}
