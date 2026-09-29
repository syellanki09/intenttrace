package io.intenttrace.pipeline

import io.intenttrace.pipeline.context.ContextResolver
import io.intenttrace.pipeline.context.InMemoryContextStore
import io.intenttrace.pipeline.model.Context
import io.intenttrace.pipeline.model.DegradationReason
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferencePipelineTest {

    @Test
    fun `decision resolves latest eligible context`() {
        val store = InMemoryContextStore()

        store.save(
            context(
                version = 1,
                committedAt = "2026-01-01T00:00:01Z",
                preferredCategory = "travel"
            )
        )

        store.save(
            context(
                version = 2,
                committedAt = "2026-01-01T00:00:02Z",
                preferredCategory = "technology"
            )
        )

        val pipeline = ReferencePipeline(store)

        val decision = pipeline.decide(
            decisionId = "decision-001",
            contextId = "user-001",
            decidedAt =
                Instant.parse("2026-01-01T00:00:03Z")
        )

        assertEquals(2L, decision.usedContextVersion)
        assertEquals(
            2L,
            decision.latestEligibleContextVersion
        )
        assertEquals("technology", decision.outcome)

        assertFalse(decision.degraded)
        assertNull(decision.degradationReason)
    }

    @Test
    fun `missing preferred category is recorded as degraded`() {
        val store = InMemoryContextStore()

        store.save(
            context(
                version = 1,
                committedAt = "2026-01-01T00:00:01Z"
            )
        )

        val pipeline = ReferencePipeline(store)

        val decision = pipeline.decide(
            decisionId = "decision-001",
            contextId = "user-001",
            decidedAt =
                Instant.parse("2026-01-01T00:00:02Z")
        )

        assertEquals("default", decision.outcome)

        assertTrue(decision.degraded)

        assertEquals(
            DegradationReason.MISSING_PREFERRED_CATEGORY,
            decision.degradationReason
        )
    }

    @Test
    fun `decision exposes stale context against latest eligible version`() {
        val store = InMemoryContextStore()

        store.save(
            context(
                version = 1,
                committedAt = "2026-01-01T00:00:01Z",
                preferredCategory = "travel"
            )
        )

        store.save(
            context(
                version = 2,
                committedAt = "2026-01-01T00:00:02Z",
                preferredCategory = "technology"
            )
        )

        val pinnedResolver =
            ContextResolver { _, _ ->
                store.find(
                    contextId = "user-001",
                    version = 1
                )
            }

        val pipeline =
            ReferencePipeline(
                contextStore = store,
                contextResolver = pinnedResolver
            )

        val decision = pipeline.decide(
            decisionId = "decision-001",
            contextId = "user-001",
            decidedAt =
                Instant.parse("2026-01-01T00:00:03Z")
        )

        assertEquals(1L, decision.usedContextVersion)

        assertEquals(
            2L,
            decision.latestEligibleContextVersion
        )

        assertEquals("travel", decision.outcome)
    }

    @Test
    fun `resolver cannot use context committed after decision`() {
        val store = InMemoryContextStore()

        store.save(
            context(
                version = 1,
                committedAt = "2026-01-01T00:00:01Z",
                preferredCategory = "travel"
            )
        )

        store.save(
            context(
                version = 2,
                committedAt = "2026-01-01T00:00:03Z",
                preferredCategory = "technology"
            )
        )

        val futureResolver =
            ContextResolver { _, _ ->
                store.find(
                    contextId = "user-001",
                    version = 2
                )
            }

        val pipeline =
            ReferencePipeline(
                contextStore = store,
                contextResolver = futureResolver
            )

        assertFailsWith<IllegalArgumentException> {
            pipeline.decide(
                decisionId = "decision-001",
                contextId = "user-001",
                decidedAt =
                    Instant.parse(
                        "2026-01-01T00:00:02Z"
                    )
            )
        }
    }

    @Test
    fun `decision fails when no context was eligible`() {
        val store = InMemoryContextStore()

        store.save(
            context(
                version = 1,
                committedAt = "2026-01-01T00:00:02Z",
                preferredCategory = "travel"
            )
        )

        val pipeline = ReferencePipeline(store)

        assertFailsWith<IllegalStateException> {
            pipeline.decide(
                decisionId = "decision-001",
                contextId = "user-001",
                decidedAt =
                    Instant.parse(
                        "2026-01-01T00:00:01Z"
                    )
            )
        }
    }

    private fun context(
        version: Long,
        committedAt: String,
        preferredCategory: String? = null
    ): Context {

        val attributes =
            preferredCategory
                ?.let {
                    mapOf(
                        "preferredCategory" to it
                    )
                }
                ?: emptyMap()

        return Context(
            id = "user-001",
            version = version,
            committedAt = Instant.parse(committedAt),
            attributes = attributes
        )
    }
}
