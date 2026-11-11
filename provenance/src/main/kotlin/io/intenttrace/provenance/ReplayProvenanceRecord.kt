@file:kotlinx.serialization.UseSerializers(FailureClassSerializer::class, InvariantStatusSerializer::class)

package io.intenttrace.provenance

import io.intenttrace.evaluation.FailureClass
import io.intenttrace.evaluation.InvariantStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class ReplayDeliveryEvidence(val eventId: String, val points: Long, val applied: Boolean)

/** Delivery order is list order; no wall-clock or durable-storage guarantee is implied. */
@Serializable
data class ReplayProvenanceRecord(
    val schemaVersion: Int = 1,
    val reproduction: ReproductionMetadata,
    val caseId: String,
    val policy: String,
    val deliveries: List<ReplayDeliveryEvidence>,
    val expectedTotal: Long,
    val observedTotal: Long,
    val failureClassId: FailureClass = FailureClass.DUPLICATE_REPLAY,
    val status: InvariantStatus,
    val detected: Boolean? = null
) {
    init {
        require(schemaVersion == 1 && failureClassId == FailureClass.DUPLICATE_REPLAY)
        require(caseId.isNotBlank() && policy in setOf("apply-every-delivery", "deduplicate-by-event-id"))
    }
}

object ReplayProvenanceJson {
    private val json = Json { encodeDefaults = true }
    fun encode(record: ReplayProvenanceRecord): String = json.encodeToString(record)
    fun decode(value: String): ReplayProvenanceRecord = json.decodeFromString(value)
}
