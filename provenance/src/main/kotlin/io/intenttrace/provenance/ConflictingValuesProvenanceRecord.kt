@file:kotlinx.serialization.UseSerializers(InstantSerializer::class, FailureClassSerializer::class, InvariantStatusSerializer::class)

package io.intenttrace.provenance

import io.intenttrace.evaluation.FailureClass
import io.intenttrace.evaluation.InvariantStatus
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** What one source reported for the fact. List order is arrival order. */
@Serializable
data class SourceObservationEvidence(val source: String, val version: Long, val committedAt: Instant, val value: String) {
    init { require(source.isNotBlank() && version > 0 && value.isNotBlank()) }
}

@Serializable
data class ConflictResolutionEvidence(val rule: String, val selectedSource: String) {
    init { require(rule.isNotBlank() && selectedSource.isNotBlank()) }
}

@Serializable
data class ConflictingValuesProvenanceRecord(
    val schemaVersion: Int = 1,
    val reproduction: ReproductionMetadata,
    val caseId: String,
    val policy: String,
    val factKey: String,
    val decidedAt: Instant,
    val observations: List<SourceObservationEvidence>,
    val usedSource: String,
    val usedValue: String,
    val resolution: ConflictResolutionEvidence?,
    val failureClassId: FailureClass = FailureClass.CONFLICTING_VALUES,
    val status: InvariantStatus,
    val detected: Boolean? = null
) {
    init {
        require(schemaVersion == 1 && failureClassId == FailureClass.CONFLICTING_VALUES)
        require(caseId.isNotBlank() && factKey.isNotBlank() && usedSource.isNotBlank() && usedValue.isNotBlank())
        require(policy in setOf("last-arrival", "prefer-primary-and-record"))
        require(observations.none { it.committedAt.isAfter(decidedAt) }) { "Observations must precede the decision" }
    }
}

object ConflictingValuesProvenanceJson {
    private val json = Json { encodeDefaults = true }
    fun encode(record: ConflictingValuesProvenanceRecord): String = json.encodeToString(record)
    fun decode(value: String): ConflictingValuesProvenanceRecord = json.decodeFromString(value)
}
