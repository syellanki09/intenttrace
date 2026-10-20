package io.intenttrace.injectors.context

import io.intenttrace.pipeline.ReferencePipeline
import io.intenttrace.pipeline.context.InMemoryContextStore
import io.intenttrace.pipeline.model.Context
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaleContextResolverTest {
    private val decidedAt = Instant.parse("2026-01-01T00:00:03Z")

    @Test
    fun `stale injection changes fresh decision into freshness violation`() {
        val store = history()
        val controlPipeline = ReferencePipeline(contextStore = store)
        val injectedPipeline = ReferencePipeline(
            contextStore = store,
            contextResolver = StaleContextResolver(store, pinnedVersion = 1)
        )
        val control = controlPipeline.decide("control-001", "user-001", decidedAt)
        val injected = injectedPipeline.decide("injected-001", "user-001", decidedAt)

        assertEquals(2L, control.usedContextVersion)
        assertEquals(2L, control.latestEligibleContextVersion)
        assertEquals("technology", control.outcome)
        assertFalse(control.freshnessViolated)
        assertFalse(control.degraded)
        assertNull(control.degradationReason)

        assertEquals(1L, injected.usedContextVersion)
        assertEquals(2L, injected.latestEligibleContextVersion)
        assertEquals("travel", injected.outcome)
        assertTrue(injected.freshnessViolated)
        assertFalse(injected.degraded)
        assertNull(injected.degradationReason)

        repeat(3) {
            assertEquals(control, controlPipeline.decide("control-001", "user-001", decidedAt))
            assertEquals(injected, injectedPipeline.decide("injected-001", "user-001", decidedAt))
        }
        assertEquals(context(1, "2026-01-01T00:00:01Z", "travel"), store.find("user-001", 1))
        assertEquals(context(2, "2026-01-01T00:00:02Z", "technology"), store.find("user-001", 2))
        assertEquals(2L, store.latestEligible("user-001", decidedAt)?.version)
    }

    @Test
    fun `unknown pinned version fails fast`() {
        val resolver = StaleContextResolver(history(), pinnedVersion = 3)

        val error = assertFailsWith<IllegalArgumentException> {
            resolver.resolve("user-001", decidedAt)
        }
        assertEquals("Pinned context version 3 does not exist for user-001", error.message)
    }

    @Test
    fun `pinning latest eligible version fails because it is not stale`() {
        val resolver = StaleContextResolver(history(), pinnedVersion = 2)

        val error = assertFailsWith<IllegalArgumentException> {
            resolver.resolve("user-001", decidedAt)
        }
        assertEquals(
            "Pinned context version 2 is not stale relative to latest eligible version 2",
            error.message
        )
    }

    @Test
    fun `pinned future context fails eligibility validation`() {
        val resolver = StaleContextResolver(history(), pinnedVersion = 2)

        val error = assertFailsWith<IllegalArgumentException> {
            resolver.resolve("user-001", Instant.parse("2026-01-01T00:00:01Z"))
        }
        assertEquals("Pinned context version 2 was not eligible at decision time", error.message)
    }

    @Test
    fun `nonpositive pinned versions fail at construction`() {
        for (version in listOf(0L, -1L)) {
            assertFailsWith<IllegalArgumentException> {
                StaleContextResolver(history(), pinnedVersion = version)
            }
        }
    }

    @Test
    fun `blank context identity fails fast`() {
        val resolver = StaleContextResolver(history(), pinnedVersion = 1)

        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(" ", decidedAt)
        }
    }

    @Test
    fun `missing or not yet eligible history fails fast`() {
        val resolver = StaleContextResolver(history(), pinnedVersion = 1)

        for ((id, boundary) in listOf(
            "missing" to decidedAt,
            "user-001" to Instant.parse("2026-01-01T00:00:00Z")
        )) {
            val error = assertFailsWith<IllegalArgumentException> {
                resolver.resolve(id, boundary)
            }
            assertEquals(
                "Cannot inject stale context when no context is eligible for $id at $boundary",
                error.message
            )
        }
    }

    private fun history(): InMemoryContextStore = InMemoryContextStore().apply {
        save(context(1, "2026-01-01T00:00:01Z", "travel"))
        save(context(2, "2026-01-01T00:00:02Z", "technology"))
    }

    private fun context(
        version: Long,
        committedAt: String,
        preferredCategory: String
    ): Context = Context(
        id = "user-001",
        version = version,
        committedAt = Instant.parse(committedAt),
        attributes = mapOf("preferredCategory" to preferredCategory)
    )
}
