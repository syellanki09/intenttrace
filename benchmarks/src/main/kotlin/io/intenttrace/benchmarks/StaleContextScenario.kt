package io.intenttrace.benchmarks

import io.intenttrace.evaluation.FreshnessInvariant
import io.intenttrace.injectors.context.StaleContextResolver
import io.intenttrace.pipeline.ReferencePipeline
import io.intenttrace.pipeline.context.InMemoryContextStore
import io.intenttrace.pipeline.model.Context
import io.intenttrace.provenance.ExecutionKind
import io.intenttrace.provenance.ProvenanceRecord
import io.intenttrace.provenance.ProvenanceRecorder
import io.intenttrace.provenance.ReproductionMetadata
import java.time.Instant

/** Fixed synthetic inputs isolate the resolver as the only difference between executions. */
class StaleContextScenario {
    fun run(metadata: ReproductionMetadata): List<ProvenanceRecord> {
        require(metadata.scenarioName == "stale-context" && metadata.scenarioVersion == "1")
        val boundary = Instant.parse("2026-01-01T00:00:03Z")
        val store = InMemoryContextStore().apply {
            save(Context("user-001", 1, boundary.minusSeconds(2), mapOf("preferredCategory" to "travel")))
            save(Context("user-001", 2, boundary.minusSeconds(1), mapOf("preferredCategory" to "technology")))
        }
        val executions = listOf(
            ExecutionKind.CONTROL to ReferencePipeline(store),
            ExecutionKind.INJECTED to ReferencePipeline(store, StaleContextResolver(store, 1))
        )
        val reproduction = metadata.copy(
            seed = null,
            configuration = metadata.configuration + mapOf(
                "contextId" to "user-001", "pinnedVersion" to "1", "decidedAt" to boundary.toString()
            )
        )
        return executions.map { (kind, pipeline) ->
            val decision = pipeline.decide("stale-context-${kind.name.lowercase()}", "user-001", boundary)
            ProvenanceRecorder().record(decision, FreshnessInvariant().evaluate(decision), store, reproduction, kind)
        }
    }
}
