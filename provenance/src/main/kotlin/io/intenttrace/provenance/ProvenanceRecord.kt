@file:kotlinx.serialization.UseSerializers(InstantSerializer::class, FailureClassSerializer::class, InvariantStatusSerializer::class)

package io.intenttrace.provenance

import io.intenttrace.evaluation.FailureClass
import io.intenttrace.evaluation.InvariantStatus
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReproductionMetadata(
    val scenarioName: String,
    val scenarioVersion: String,
    val sourceCommitSha: String,
    val seed: Long?,
    val runtime: String,
    val jdk: String,
    val operatingSystem: String,
    val configuration: Map<String, String>
) {
    init {
        require(listOf(scenarioName, scenarioVersion, runtime, jdk, operatingSystem).all { it.isNotBlank() })
        require(sourceCommitSha.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))) {
            "Source commit must be a full Git object ID"
        }
    }
}

@Serializable
enum class ExecutionKind { CONTROL, INJECTED }

@Serializable
data class ContextEvidence(
    val id: String,
    val version: Long,
    val committedAt: Instant,
    val attributes: Map<String, String>
)

@Serializable
data class EvaluationEvidence(
    @SerialName("failureClassId") val failureClass: FailureClass,
    val status: InvariantStatus,
    val expectedVersion: Long?,
    val observedVersion: Long?,
    val detected: Boolean?,
    val explanation: String
)

/** A diagnostic snapshot, separate from the pipeline's Decision model. */
@Serializable
data class ProvenanceRecord(
    val schemaVersion: Int = 1,
    val reproduction: ReproductionMetadata,
    val execution: ExecutionKind,
    val decisionId: String,
    val contextId: String,
    val decidedAt: Instant,
    val outcome: String,
    val degradationReason: String?,
    val expectedInvariant: String,
    val usedContext: ContextEvidence?,
    val latestEligibleContext: ContextEvidence?,
    val evaluation: EvaluationEvidence,
    val eventId: String? = null,
    val eventAt: Instant? = null,
    val ingestedAt: Instant? = null
)
