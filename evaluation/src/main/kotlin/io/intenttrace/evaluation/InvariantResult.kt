package io.intenttrace.evaluation

/**
 * Harness evaluation and a separately supplied observation from the evaluated system.
 * A null detection observation means no signal was supplied, not detection failure.
 */
data class InvariantResult(
    val failureClass: FailureClass,
    val status: InvariantStatus,
    val expectedVersion: Long?,
    val observedVersion: Long?,
    val detected: Boolean?,
    val explanation: String
) {
    val failureClassId: String
        get() = failureClass.id

    val violated: Boolean
        get() = status == InvariantStatus.VIOLATED
}
