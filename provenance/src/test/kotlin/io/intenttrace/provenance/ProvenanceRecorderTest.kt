package io.intenttrace.provenance

import io.intenttrace.evaluation.FailureClass
import io.intenttrace.evaluation.FreshnessInvariant
import io.intenttrace.evaluation.InvariantStatus
import io.intenttrace.injectors.context.StaleContextResolver
import io.intenttrace.pipeline.ReferencePipeline
import io.intenttrace.pipeline.context.ContextResolver
import io.intenttrace.pipeline.context.InMemoryContextStore
import io.intenttrace.pipeline.model.Context
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProvenanceRecorderTest {
    private val boundary = Instant.parse("2026-01-01T00:00:03Z")
    private val recorder = ProvenanceRecorder()
    private val metadata = ReproductionMetadata(
        "stale-context", "1", "a".repeat(40), null,
        "test-runtime", "17", "test-os", mapOf("pinnedVersion" to "1")
    )

    @Test
    fun `paired provenance preserves evidence and round trips as compact JSON`() {
        val store = history()
        val pipelines = listOf(
            ExecutionKind.CONTROL to ReferencePipeline(store),
            ExecutionKind.INJECTED to ReferencePipeline(store, StaleContextResolver(store, 1))
        )
        for ((kind, pipeline) in pipelines) {
            val decision = pipeline.decide(kind.name, "user-001", boundary)
            val record = recorder.record(decision, FreshnessInvariant().evaluate(decision), store, metadata, kind)
            val encoded = ProvenanceJson.encode(record)
            assertEquals(record, ProvenanceJson.decode(encoded))
            assertEquals(false, encoded.contains('\n'))
            assertEquals(1, record.schemaVersion)
            assertEquals(if (kind == ExecutionKind.CONTROL) 2L else 1L, record.usedContext?.version)
            assertEquals(2L, record.latestEligibleContext?.version)
            assertEquals(if (kind == ExecutionKind.CONTROL) "technology" else "travel", record.outcome)
            assertEquals(boundary.minusSeconds(1), record.latestEligibleContext?.committedAt)
            assertEquals(boundary, record.decidedAt)
            assertNull(record.evaluation.detected)
            assertNull(record.degradationReason)
            assertNull(record.eventId)
            assertNull(record.eventAt)
            assertNull(record.ingestedAt)
            val root = Json.parseToJsonElement(encoded).jsonObject
            assertEquals("stale-context", root.getValue("evaluation").jsonObject.getValue("failureClassId").jsonPrimitive.content)
            assertEquals("null", root.getValue("evaluation").jsonObject.getValue("detected").toString())
            repeat(3) {
                assertEquals(encoded, ProvenanceJson.encode(recorder.record(
                    decision, FreshnessInvariant().evaluate(decision), store, metadata, kind
                )))
            }
        }
        assertEquals(2L, store.latestEligible("user-001", boundary)?.version)
    }

    @Test
    fun `missing and unavailable context retain explicit null evidence`() {
        for (noHistory in listOf(true, false)) {
            val store = if (noHistory) InMemoryContextStore() else history()
            val pipeline = ReferencePipeline(store, ContextResolver { _, _ -> null })
            val decision = pipeline.decide("missing", "user-001", boundary)
            val record = recorder.record(decision, FreshnessInvariant().evaluate(decision), store, metadata, ExecutionKind.CONTROL)
            assertEquals(InvariantStatus.NOT_APPLICABLE, record.evaluation.status)
            assertNull(record.usedContext)
            assertEquals(if (noHistory) null else 2L, record.latestEligibleContext?.version)
            assertEquals(if (noHistory) "NO_ELIGIBLE_CONTEXT" else "CONTEXT_UNAVAILABLE", record.degradationReason)
            assertEquals(record, ProvenanceJson.decode(ProvenanceJson.encode(record)))
        }
    }

    @Test
    fun `detection observations survive serialization without changing ground truth`() {
        val store = history()
        val decision = ReferencePipeline(store).decide("fresh", "user-001", boundary)
        for (signal in listOf(null, false, true)) {
            val record = recorder.record(decision, FreshnessInvariant().evaluate(decision, signal), store, metadata, ExecutionKind.CONTROL)
            val decoded = ProvenanceJson.decode(ProvenanceJson.encode(record))
            assertEquals(signal, decoded.evaluation.detected)
            assertEquals(InvariantStatus.SATISFIED, decoded.evaluation.status)
        }
    }

    @Test
    fun `configuration and attributes are stable snapshots with escaped content`() {
        val store = history()
        val configuration = mutableMapOf("z" to "quotes: \" and newline\n", "a" to "first")
        val decision = ReferencePipeline(store).decide("fresh", "user-001", boundary)
        val record = recorder.record(decision, FreshnessInvariant().evaluate(decision), store,
            metadata.copy(configuration = configuration), ExecutionKind.CONTROL)
        val encoded = ProvenanceJson.encode(record)
        configuration.clear()
        assertEquals(encoded, ProvenanceJson.encode(record))
        assertEquals(record, ProvenanceJson.decode(encoded))
        assertFailsWith<UnsupportedOperationException> { (record.reproduction.configuration as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (record.usedContext!!.attributes as MutableMap).clear() }
    }

    @Test
    fun `inconsistent evaluation and changed history are rejected`() {
        val store = history()
        val decision = ReferencePipeline(store).decide("fresh", "user-001", boundary)
        val evaluation = FreshnessInvariant().evaluate(decision)
        assertFailsWith<IllegalArgumentException> {
            recorder.record(decision, evaluation.copy(observedVersion = 1), store, metadata, ExecutionKind.CONTROL)
        }
        store.save(Context("user-001", 3, boundary, emptyMap()))
        assertFailsWith<IllegalArgumentException> {
            recorder.record(decision, evaluation, store, metadata, ExecutionKind.CONTROL)
        }
    }

    @Test
    fun `all canonical failure class IDs round trip and unknown IDs are rejected`() {
        val store = history()
        val decision = ReferencePipeline(store).decide("fresh", "user-001", boundary)
        val record = recorder.record(decision, FreshnessInvariant().evaluate(decision), store, metadata, ExecutionKind.CONTROL)
        for (failureClass in FailureClass.entries) {
            val changed = record.copy(evaluation = record.evaluation.copy(failureClass = failureClass))
            assertEquals(changed, ProvenanceJson.decode(ProvenanceJson.encode(changed)))
        }
        assertFailsWith<SerializationException> {
            ProvenanceJson.decode(ProvenanceJson.encode(record).replace("\"failureClassId\":\"stale-context\"", "\"failureClassId\":\"typo\""))
        }
    }

    @Test
    fun `used version missing from supplied history is rejected`() {
        val original = history()
        val decision = ReferencePipeline(original, StaleContextResolver(original, 1))
            .decide("stale", "user-001", boundary)
        val incomplete = InMemoryContextStore().apply { save(original.find("user-001", 2)!!) }
        assertFailsWith<IllegalArgumentException> {
            recorder.record(decision, FreshnessInvariant().evaluate(decision), incomplete, metadata, ExecutionKind.INJECTED)
        }
    }

    private fun history() = InMemoryContextStore().apply {
        save(Context("user-001", 1, boundary.minusSeconds(2), mapOf("preferredCategory" to "travel")))
        save(Context("user-001", 2, boundary.minusSeconds(1), mapOf("preferredCategory" to "technology")))
    }
}
