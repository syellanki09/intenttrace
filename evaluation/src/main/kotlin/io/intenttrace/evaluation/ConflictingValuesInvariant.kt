package io.intenttrace.evaluation

/** What one source reported for a fact. List order is arrival order. */
data class SourceObservation(val source: String, val version: Long, val value: String) {
    init { require(source.isNotBlank() && version > 0 && value.isNotBlank()) }
}

/** A host's record that sources disagreed, the rule it applied, and the source the rule selected. */
data class ConflictResolution(val rule: String, val selectedSource: String) {
    init { require(rule.isNotBlank() && selectedSource.isNotBlank()) }
}

data class ConflictingValuesInvariantResult(
    val conflict: Boolean,
    val usedSource: String,
    val usedValue: String,
    val status: InvariantStatus,
    val detected: Boolean?
) {
    val failureClass: FailureClass get() = FailureClass.CONFLICTING_VALUES
}

/**
 * Where two or more sources describe the same fact and disagree, the decision
 * resolves the conflict by a defined rule and records that it did so. Picking
 * one value by arrival order or last writer without a record violates the
 * invariant. Agreeing sources need no record.
 */
class ConflictingValuesInvariant {
    fun evaluate(
        observations: List<SourceObservation>,
        usedSource: String,
        resolution: ConflictResolution?,
        detected: Boolean? = null
    ): ConflictingValuesInvariantResult {
        require(observations.size >= 2) { "A conflict check needs at least two sources" }
        require(observations.map { it.source }.distinct().size == observations.size) { "Sources must be unique" }
        val used = observations.singleOrNull { it.source == usedSource }
        requireNotNull(used) { "The decision must use a value that one of the sources reported" }
        require(resolution == null || observations.any { it.source == resolution.selectedSource }) {
            "A resolution must select one of the observed sources"
        }
        val conflict = observations.map { it.value }.distinct().size > 1
        val satisfied = !conflict || (resolution != null && resolution.selectedSource == usedSource)
        return ConflictingValuesInvariantResult(conflict, usedSource, used.value,
            if (satisfied) InvariantStatus.SATISFIED else InvariantStatus.VIOLATED, detected)
    }
}
