package io.intenttrace.pipeline.context

import io.intenttrace.pipeline.model.Context
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContextStoreTest {

    @Test
    fun `rejects higher version committed before lower version`() {
        val store = InMemoryContextStore()

        store.save(
            context(
                version = 1,
                committedAt = "2026-01-01T00:00:02Z"
            )
        )

        assertFailsWith<IllegalArgumentException> {
            store.save(
                context(
                    version = 2,
                    committedAt =
                        "2026-01-01T00:00:01Z"
                )
            )
        }
    }

    @Test
    fun `rejects lower version committed after higher version`() {
        val store = InMemoryContextStore()

        store.save(
            context(
                version = 2,
                committedAt = "2026-01-01T00:00:02Z"
            )
        )

        assertFailsWith<IllegalArgumentException> {
            store.save(
                context(
                    version = 1,
                    committedAt =
                        "2026-01-01T00:00:03Z"
                )
            )
        }
    }

    @Test
    fun `allows logically ordered history inserted out of order`() {
        val store = InMemoryContextStore()

        store.save(
            context(
                version = 2,
                committedAt = "2026-01-01T00:00:02Z"
            )
        )

        store.save(
            context(
                version = 1,
                committedAt = "2026-01-01T00:00:01Z"
            )
        )

        val latest =
            store.latestEligible(
                contextId = "user-001",
                decidedAt =
                    Instant.parse(
                        "2026-01-01T00:00:03Z"
                    )
            )

        assertEquals(2L, latest?.version)
    }

    @Test
    fun `rejects duplicate context version`() {
        val store = InMemoryContextStore()

        store.save(
            context(
                version = 1,
                committedAt = "2026-01-01T00:00:01Z"
            )
        )

        assertFailsWith<IllegalArgumentException> {
            store.save(
                context(
                    version = 1,
                    committedAt =
                        "2026-01-01T00:00:01Z"
                )
            )
        }
    }

    private fun context(
        version: Long,
        committedAt: String
    ): Context =
        Context(
            id = "user-001",
            version = version,
            committedAt =
                Instant.parse(committedAt),
            attributes = emptyMap()
        )
}
