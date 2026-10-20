package io.intenttrace.provenance

import io.intenttrace.evaluation.FreshnessInvariant
import io.intenttrace.evaluation.InvariantResult
import io.intenttrace.pipeline.context.ContextStore
import io.intenttrace.pipeline.model.Context
import io.intenttrace.pipeline.model.Decision
import java.util.Collections
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Called by the harness after decision and evaluation; never changes the pipeline or store. */
class ProvenanceRecorder {
    fun record(
        decision: Decision,
        evaluation: InvariantResult,
        contextStore: ContextStore,
        reproduction: ReproductionMetadata,
        execution: ExecutionKind
    ): ProvenanceRecord {
        require(evaluation == FreshnessInvariant().evaluate(decision, evaluation.detected)) {
            "Evaluation must match the decision's recorded context versions"
        }
        val latest = contextStore.latestEligible(decision.contextId, decision.decidedAt)
        require(latest?.version == decision.latestEligibleContextVersion) {
            "Latest eligible history must match the decision boundary"
        }
        latest?.let {
            require(it.id == decision.contextId && !it.committedAt.isAfter(decision.decidedAt)) {
                "Latest eligible context must match the decision identity and boundary"
            }
        }
        val used = decision.usedContextVersion?.let { version ->
            requireNotNull(contextStore.find(decision.contextId, version)) {
                "Used context version is missing from history"
            }.also {
                require(it.id == decision.contextId && it.version == version && !it.committedAt.isAfter(decision.decidedAt)) {
                    "Used context must match the decision and be eligible"
                }
            }
        }
        return ProvenanceRecord(
            reproduction = reproduction.copy(configuration = snapshot(reproduction.configuration)),
            execution = execution,
            decisionId = decision.id,
            contextId = decision.contextId,
            decidedAt = decision.decidedAt,
            outcome = decision.outcome,
            degradationReason = decision.degradationReason?.name,
            expectedInvariant = "A decision uses the latest context committed at or before its decision boundary.",
            usedContext = used?.evidence(),
            latestEligibleContext = latest?.evidence(),
            evaluation = EvaluationEvidence(
                evaluation.failureClass, evaluation.status, evaluation.expectedVersion,
                evaluation.observedVersion, evaluation.detected, evaluation.explanation
            )
        )
    }

    private fun Context.evidence() = ContextEvidence(id, version, committedAt, snapshot(attributes))

    private fun snapshot(values: Map<String, String>): Map<String, String> =
        Collections.unmodifiableMap(values.toSortedMap())
}

/** Compact JSON is suitable for one-record-per-line output by a future runner. */
object ProvenanceJson {
    private val json = Json { encodeDefaults = true }
    fun encode(record: ProvenanceRecord): String = json.encodeToString(record)
    fun decode(value: String): ProvenanceRecord = json.decodeFromString(value)
}
