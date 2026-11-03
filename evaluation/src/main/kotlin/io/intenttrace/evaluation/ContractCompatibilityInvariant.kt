package io.intenttrace.evaluation

/** An observed client outcome for one controlled logical input. */
data class ClientContractDecision(
    val inputId: String,
    val clientId: String,
    val contractVersion: Long,
    val outcome: String
) {
    init {
        require(inputId.isNotBlank() && clientId.isNotBlank()) { "Input and client identifiers must not be blank" }
        require(contractVersion > 0) { "Contract version must be positive" }
        require(outcome.isNotBlank()) { "Outcome must not be blank" }
    }
}

data class ContractCompatibilityResult(
    val reference: ClientContractDecision,
    val observed: ClientContractDecision,
    val status: InvariantStatus,
    val detected: Boolean?
) {
    val failureClass: FailureClass
        get() = FailureClass.CONTRACT_INCOMPATIBILITY

    val violated: Boolean
        get() = status == InvariantStatus.VIOLATED
}

/**
 * Compares a harness-supplied reference outcome with another client/version.
 * Matching outcomes establish consistency for this input, not universal compatibility.
 */
class ContractCompatibilityInvariant {
    fun evaluate(
        reference: ClientContractDecision,
        observed: ClientContractDecision,
        detected: Boolean? = null
    ): ContractCompatibilityResult {
        require(reference.inputId == observed.inputId) { "Compatibility comparisons require the same logical input" }
        return ContractCompatibilityResult(
            reference, observed,
            if (reference.outcome == observed.outcome) InvariantStatus.SATISFIED else InvariantStatus.VIOLATED,
            detected
        )
    }
}
