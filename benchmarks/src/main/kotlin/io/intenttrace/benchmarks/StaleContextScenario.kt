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

/** Explicit synthetic inputs isolate the resolver as the only difference between executions. */
class StaleContextScenario {
    fun run(
        metadata: ReproductionMetadata,
        configuration: StaleContextConfiguration = StaleContextConfiguration()
    ): List<ProvenanceRecord> {
        require(metadata.scenarioName == "stale-context" && metadata.scenarioVersion == "1")
        val boundary = configuration.decidedAt
        val store = InMemoryContextStore().apply {
            save(Context(configuration.contextId, configuration.olderVersion, configuration.olderCommittedAt,
                mapOf("preferredCategory" to configuration.olderCategory)))
            save(Context(configuration.contextId, configuration.latestVersion, configuration.latestCommittedAt,
                mapOf("preferredCategory" to configuration.latestCategory)))
        }
        val executions = listOf(
            ExecutionKind.CONTROL to ReferencePipeline(store),
            ExecutionKind.INJECTED to ReferencePipeline(store, StaleContextResolver(store, configuration.olderVersion))
        )
        val reproduction = metadata.copy(
            seed = null,
            configuration = metadata.configuration + configuration.metadata()
        )
        return executions.map { (kind, pipeline) ->
            val decision = pipeline.decide("stale-context-${kind.name.lowercase()}", configuration.contextId, boundary)
            ProvenanceRecorder().record(decision, FreshnessInvariant().evaluate(decision), store, reproduction, kind)
        }
    }
}
