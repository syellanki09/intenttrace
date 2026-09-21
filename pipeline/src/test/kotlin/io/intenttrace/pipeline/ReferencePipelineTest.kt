package io.intenttrace.pipeline

import io.intenttrace.pipeline.model.Context
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReferencePipelineTest {

    private val pipeline = ReferencePipeline()

    @Test
    fun `decision records the context version used`() {
        val context = Context(
            id = "user-001",
            version = 2,
            committedAt = Instant.parse("2026-01-01T00:00:02Z"),
            attributes = mapOf(
                "preferredCategory" to "technology"
            )
        )

        val decision = pipeline.decide(
            decisionId = "decision-001",
            context = context,
            decidedAt = Instant.parse("2026-01-01T00:00:03Z")
        )

        assertEquals("user-001", decision.contextId)
        assertEquals(2L, decision.contextVersion)
        assertEquals("technology", decision.outcome)
    }

    @Test
    fun `newer context produces decision from newer version`() {
        val v1 = Context(
            id = "user-001",
            version = 1,
            committedAt = Instant.parse("2026-01-01T00:00:01Z"),
            attributes = mapOf(
                "preferredCategory" to "travel"
            )
        )

        val v2 = Context(
            id = "user-001",
            version = 2,
            committedAt = Instant.parse("2026-01-01T00:00:02Z"),
            attributes = mapOf(
                "preferredCategory" to "technology"
            )
        )

        val firstDecision = pipeline.decide(
            decisionId = "decision-001",
            context = v1,
            decidedAt = Instant.parse("2026-01-01T00:00:01Z")
        )

        val secondDecision = pipeline.decide(
            decisionId = "decision-002",
            context = v2,
            decidedAt = Instant.parse("2026-01-01T00:00:03Z")
        )

        assertEquals(1L, firstDecision.contextVersion)
        assertEquals("travel", firstDecision.outcome)

        assertEquals(2L, secondDecision.contextVersion)
        assertEquals("technology", secondDecision.outcome)
    }

    @Test
    fun `missing preferred category uses deterministic default`() {
        val context = Context(
            id = "user-001",
            version = 1,
            committedAt = Instant.parse("2026-01-01T00:00:01Z"),
            attributes = emptyMap()
        )

        val decision = pipeline.decide(
            decisionId = "decision-001",
            context = context,
            decidedAt = Instant.parse("2026-01-01T00:00:02Z")
        )

        assertEquals("default", decision.outcome)
    }

    @Test
    fun `decision cannot occur before context commit`() {
        val context = Context(
            id = "user-001",
            version = 1,
            committedAt = Instant.parse("2026-01-01T00:00:02Z"),
            attributes = mapOf(
                "preferredCategory" to "travel"
            )
        )

        assertFailsWith<IllegalArgumentException> {
            pipeline.decide(
                decisionId = "decision-001",
                context = context,
                decidedAt = Instant.parse("2026-01-01T00:00:01Z")
            )
        }
    }
}