@file:kotlinx.serialization.UseSerializers(FailureClassSerializer::class, InvariantStatusSerializer::class)

package io.intenttrace.provenance

import io.intenttrace.evaluation.FailureClass
import io.intenttrace.evaluation.InvariantStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ContractDurationUnit { MILLISECONDS, SECONDS }

@Serializable
data class ContractClientEvidence(
    val inputId: String,
    val clientId: String,
    val contractVersion: Long,
    val outcome: String
)

/** Contract evidence has no fabricated context versions or event timestamps. */
@Serializable
data class ContractProvenanceRecord(
    val schemaVersion: Int = 1,
    val reproduction: ReproductionMetadata,
    val caseId: String,
    val logicalDurationMillis: Long,
    val referenceWirePayload: String,
    val wirePayload: String,
    val producerUnit: ContractDurationUnit,
    val consumerUnit: ContractDurationUnit,
    val reference: ContractClientEvidence,
    val observed: ContractClientEvidence,
    val failureClassId: FailureClass,
    val status: InvariantStatus,
    val detected: Boolean?
) {
    init {
        require(schemaVersion == 1) { "Unsupported contract provenance schema version: $schemaVersion" }
        require(failureClassId == FailureClass.CONTRACT_INCOMPATIBILITY)
        require(logicalDurationMillis >= 0 && caseId.isNotBlank())
    }
}

object ContractProvenanceJson {
    private val json = Json { encodeDefaults = true }
    fun encode(record: ContractProvenanceRecord): String = json.encodeToString(record)
    fun decode(value: String): ContractProvenanceRecord = json.decodeFromString(value)
}
