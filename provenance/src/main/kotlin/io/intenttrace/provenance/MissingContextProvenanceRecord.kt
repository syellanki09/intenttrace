@file:kotlinx.serialization.UseSerializers(InstantSerializer::class, FailureClassSerializer::class, InvariantStatusSerializer::class)

package io.intenttrace.provenance

import io.intenttrace.evaluation.FailureClass
import io.intenttrace.evaluation.InvariantStatus
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class ContextSnapshotEvidence(val version: Long, val committedAt: Instant, val value: String) {
    init { require(version > 0 && value.isNotBlank()) }
}

@Serializable
data class DegradationEvidence(val reason: String, val source: String) {
    init { require(reason.isNotBlank() && source.isNotBlank()) }
}

/**
 * One decision that required a context value. [delivered] is what the read path
 * returned (null when the source answered empty); [required] is the value the
 * harness committed before the decision.
 */
@Serializable
data class MissingContextProvenanceRecord(
    val schemaVersion: Int = 1,
    val reproduction: ReproductionMetadata,
    val caseId: String,
    val policy: String,
    val contextKey: String,
    val decidedAt: Instant,
    val required: ContextSnapshotEvidence,
    val delivered: ContextSnapshotEvidence?,
    val defaultValue: String,
    val usedValue: String,
    val degradation: DegradationEvidence?,
    val failureClassId: FailureClass = FailureClass.MISSING_CONTEXT,
    val status: InvariantStatus,
    val detected: Boolean? = null
) {
    init {
        require(schemaVersion == 1 && failureClassId == FailureClass.MISSING_CONTEXT)
        require(caseId.isNotBlank() && contextKey.isNotBlank() && defaultValue.isNotBlank() && usedValue.isNotBlank())
        require(policy in setOf("default-silently", "default-and-record"))
        require(!required.committedAt.isAfter(decidedAt)) { "Required context must be committed before the decision" }
    }
}

object MissingContextProvenanceJson {
    private val json = Json { encodeDefaults = true }
    fun encode(record: MissingContextProvenanceRecord): String = json.encodeToString(record)
    fun decode(value: String): MissingContextProvenanceRecord = json.decodeFromString(value)
}
