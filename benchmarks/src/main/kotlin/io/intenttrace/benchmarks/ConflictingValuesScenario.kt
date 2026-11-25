package io.intenttrace.benchmarks

import io.intenttrace.evaluation.ConflictResolution
import io.intenttrace.evaluation.ConflictingValuesInvariant
import io.intenttrace.evaluation.SourceObservation
import io.intenttrace.provenance.ConflictResolutionEvidence
import io.intenttrace.provenance.ConflictingValuesProvenanceJson
import io.intenttrace.provenance.ConflictingValuesProvenanceRecord
import io.intenttrace.provenance.ReproductionMetadata
import io.intenttrace.provenance.SourceObservationEvidence
import java.time.Instant

/**
 * A credit decision consults two sources for an account's limit: the primary
 * store and a cache. The injected fault writes a divergent value to the cache,
 * once with a newer version (a write the primary never kept) and once under the
 * primary's own version. The host either takes whichever answer arrived last or
 * prefers the primary and records the disagreement.
 */
fun conflictingValuesRecords(metadata: ReproductionMetadata): List<ConflictingValuesProvenanceRecord> {
    require(metadata.scenarioName == "conflicting-values" && metadata.scenarioVersion == "1")
    val factKey = "account-17/credit-limit"
    val decidedAt = Instant.parse("2026-01-01T00:08:00Z")
    val primary = SourceObservationEvidence("primary", 7, Instant.parse("2026-01-01T00:04:00Z"), "500")
    val agreeing = SourceObservationEvidence("cache", 7, Instant.parse("2026-01-01T00:04:00Z"), "500")
    val newerDivergent = SourceObservationEvidence("cache", 8, Instant.parse("2026-01-01T00:06:00Z"), "750")
    val sameVersionDivergent = SourceObservationEvidence("cache", 7, Instant.parse("2026-01-01T00:04:00Z"), "750")

    fun execute(caseId: String, policy: String, cache: SourceObservationEvidence): ConflictingValuesProvenanceRecord {
        val observations = listOf(primary, cache)
        val conflict = observations.map { it.value }.distinct().size > 1
        val used = if (policy == "prefer-primary-and-record") primary else observations.last()
        val resolution = if (conflict && policy == "prefer-primary-and-record")
            ConflictResolutionEvidence("prefer-primary", "primary") else null
        val evaluation = ConflictingValuesInvariant().evaluate(
            observations.map { SourceObservation(it.source, it.version, it.value) }, used.source,
            resolution?.let { ConflictResolution(it.rule, it.selectedSource) })
        return ConflictingValuesProvenanceRecord(reproduction = metadata, caseId = caseId, policy = policy,
            factKey = factKey, decidedAt = decidedAt, observations = observations, usedSource = used.source,
            usedValue = used.value, resolution = resolution, status = evaluation.status, detected = evaluation.detected)
    }
    return listOf(execute("control", "last-arrival", agreeing),
        execute("last-arrival-wins", "last-arrival", newerDivergent),
        execute("recorded-resolution", "prefer-primary-and-record", newerDivergent),
        execute("same-version-divergence", "last-arrival", sameVersionDivergent))
}

object ConflictingValuesMain {
    @JvmStatic fun main(args: Array<String>) = runFixedScenario("conflicting-values", args, ::conflictingValuesRecords,
        ConflictingValuesProvenanceJson::encode) {
        "${it.caseId}: used ${it.usedSource}=${it.usedValue} from ${it.observations.map { o -> "${o.source}=${o.value}" }}, " +
            "resolution=${it.resolution?.rule}, ${it.status}, detected=${it.detected}"
    }
}
