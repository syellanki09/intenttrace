package io.intenttrace.benchmarks

import io.intenttrace.evaluation.ContextSnapshot
import io.intenttrace.evaluation.Degradation
import io.intenttrace.evaluation.MissingContextInvariant
import io.intenttrace.provenance.ContextSnapshotEvidence
import io.intenttrace.provenance.DegradationEvidence
import io.intenttrace.provenance.MissingContextProvenanceJson
import io.intenttrace.provenance.MissingContextProvenanceRecord
import io.intenttrace.provenance.ReproductionMetadata
import java.time.Instant

/**
 * A pricing decision needs a member's discount tier. The harness commits the
 * tier before the decision; the injected fault makes the tier source answer
 * empty while reporting healthy. The host either falls back to the default
 * tier silently or falls back and records which source failed.
 */
fun missingContextRecords(metadata: ReproductionMetadata): List<MissingContextProvenanceRecord> {
    require(metadata.scenarioName == "missing-context" && metadata.scenarioVersion == "1")
    val contextKey = "member-42/discount-tier"
    val decidedAt = Instant.parse("2026-01-01T00:08:00Z")
    val required = ContextSnapshotEvidence(4, Instant.parse("2026-01-01T00:05:00Z"), "gold")
    val defaultValue = "standard"

    fun execute(caseId: String, policy: String, sourceAnswersEmpty: Boolean): MissingContextProvenanceRecord {
        val delivered = if (sourceAnswersEmpty) null else required
        val record = policy == "default-and-record"
        val usedValue = delivered?.value ?: defaultValue
        val degradation = if (delivered == null && record) DegradationEvidence("context-source-empty", "tier-service")
            else null
        val evaluation = MissingContextInvariant().evaluate(ContextSnapshot(required.version, required.value),
            delivered?.let { ContextSnapshot(it.version, it.value) }, usedValue,
            degradation?.let { Degradation(it.reason, it.source) })
        return MissingContextProvenanceRecord(reproduction = metadata, caseId = caseId, policy = policy,
            contextKey = contextKey, decidedAt = decidedAt, required = required, delivered = delivered,
            defaultValue = defaultValue, usedValue = usedValue, degradation = degradation,
            status = evaluation.status, detected = evaluation.detected)
    }
    return listOf(execute("control", "default-silently", false), execute("silent-default", "default-silently", true),
        execute("recorded-degradation", "default-and-record", true))
}

object MissingContextMain {
    @JvmStatic fun main(args: Array<String>) = runFixedScenario("missing-context", args, ::missingContextRecords,
        MissingContextProvenanceJson::encode) {
        "${it.caseId}: used ${it.usedValue} (required ${it.required.value}), degradation=${it.degradation?.reason}, " +
            "${it.status}, detected=${it.detected}"
    }
}
