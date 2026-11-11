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
data class EventTimeDeliveryEvidence(
    val eventId: String,
    val occurredAt: Instant,
    val ingestedAt: Instant,
    val value: String
) {
    init { require(eventId.isNotBlank() && value.isNotBlank() && ingestedAt >= occurredAt) }
}

@Serializable
data class EventTimeProvenanceRecord(
    val schemaVersion: Int = 1,
    val reproduction: ReproductionMetadata,
    val caseId: String,
    val policy: String,
    val decidedAt: Instant,
    val deliveries: List<EventTimeDeliveryEvidence>,
    val expectedEventId: String?,
    val expectedValue: String?,
    val observedEventId: String?,
    val observedValue: String?,
    val failureClassId: FailureClass,
    val status: InvariantStatus,
    val detected: Boolean? = null
) {
    init {
        require(schemaVersion == 1 && failureClassId in setOf(FailureClass.STALE_CONTEXT, FailureClass.DELAYED_EVENTS))
        require(caseId.isNotBlank() && policy in setOf("arrival-order", "event-time"))
    }
}

object EventTimeProvenanceJson {
    private val json = Json { encodeDefaults = true }
    fun encode(record: EventTimeProvenanceRecord): String = json.encodeToString(record)
    fun decode(value: String): EventTimeProvenanceRecord = json.decodeFromString(value)
}
