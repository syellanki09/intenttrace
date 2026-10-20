package io.intenttrace.evaluation

import io.intenttrace.injectors.context.StaleContextResolver
import io.intenttrace.pipeline.ReferencePipeline
import io.intenttrace.pipeline.context.ContextResolver
import io.intenttrace.pipeline.context.InMemoryContextStore
import io.intenttrace.pipeline.model.Context
import io.intenttrace.pipeline.model.Decision
import io.intenttrace.pipeline.model.DegradationReason
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreshnessInvariantTest {
    private val boundary = Instant.parse("2026-01-01T00:00:03Z")
    private val evaluator = FreshnessInvariant()

    @Test
    fun `control satisfies freshness while stale injection violates it without detection signals`() {
        val store = history()
        val control = ReferencePipeline(store).decide("control", "user-001", boundary)
        val injected = ReferencePipeline(store, StaleContextResolver(store, 1))
            .decide("injected", "user-001", boundary)

        val controlResult = evaluator.evaluate(control)
        assertEquals(InvariantStatus.SATISFIED, controlResult.status)
        assertFalse(controlResult.violated)
        assertEquals(2L, controlResult.expectedVersion)
        assertEquals(2L, controlResult.observedVersion)
        assertNull(controlResult.detected)

        val injectedResult = evaluator.evaluate(injected)
        assertEquals("stale-context", injectedResult.failureClassId)
        assertEquals(InvariantStatus.VIOLATED, injectedResult.status)
        assertTrue(injectedResult.violated)
        assertEquals(2L, injectedResult.expectedVersion)
        assertEquals(1L, injectedResult.observedVersion)
        assertNull(injectedResult.detected)
        assertTrue(injectedResult.explanation.contains("version 1"))
        assertTrue(injectedResult.explanation.contains("version 2"))
    }

    @Test
    fun `no eligible context is not applicable rather than satisfied`() {
        val decision = ReferencePipeline(InMemoryContextStore())
            .decide("missing", "user-001", boundary)

        val result = evaluator.evaluate(decision)
        assertEquals(InvariantStatus.NOT_APPLICABLE, result.status)
        assertFalse(result.violated)
        assertNull(result.expectedVersion)
        assertNull(result.observedVersion)
        assertNull(result.detected)
    }

    @Test
    fun `eligible but unavailable context is not applicable rather than stale`() {
        val decision = ReferencePipeline(history(), ContextResolver { _, _ -> null })
            .decide("unavailable", "user-001", boundary)

        val result = evaluator.evaluate(decision)
        assertEquals(InvariantStatus.NOT_APPLICABLE, result.status)
        assertFalse(result.violated)
        assertEquals(2L, result.expectedVersion)
        assertNull(result.observedVersion)
        assertNull(result.detected)
    }

    @Test
    fun `supplied detection observations do not change ground truth`() {
        val store = history()
        val decision = ReferencePipeline(store, StaleContextResolver(store, 1))
            .decide("stale", "user-001", boundary)
        val baseline = evaluator.evaluate(decision)

        for (observation in listOf(true, false)) {
            assertEquals(baseline.copy(detected = observation), evaluator.evaluate(decision, observation))
        }
        val fresh = ReferencePipeline(history()).decide("fresh", "user-001", boundary)
        val falsePositive = evaluator.evaluate(fresh, detected = true)
        assertEquals(InvariantStatus.SATISFIED, falsePositive.status)
        assertEquals(true, falsePositive.detected)
    }

    @Test
    fun `missing category does not hide stale context`() {
        val decision = Decision(
            id = "degraded-stale",
            contextId = "user-001",
            usedContextVersion = 1,
            latestEligibleContextVersion = 2,
            decidedAt = boundary,
            outcome = "default",
            degradationReason = DegradationReason.MISSING_PREFERRED_CATEGORY
        )
        assertEquals(InvariantStatus.VIOLATED, evaluator.evaluate(decision).status)
    }

    private fun history(): InMemoryContextStore = InMemoryContextStore().apply {
        save(Context("user-001", 1, boundary.minusSeconds(2), mapOf("preferredCategory" to "travel")))
        save(Context("user-001", 2, boundary.minusSeconds(1), mapOf("preferredCategory" to "technology")))
    }
}
