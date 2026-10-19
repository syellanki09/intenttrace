package io.intenttrace.pipeline.context

import io.intenttrace.pipeline.model.Context
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ContextStoreTest {

    @Test
    fun `stored history is unaffected by caller or reader mutation`() {
        val store = InMemoryContextStore()
        val attributes = mutableMapOf("preferredCategory" to "travel")
        val snapshot = Context("user-001", 1,
            Instant.parse("2026-01-01T00:00:01Z"), attributes)
        store.save(snapshot)
        attributes["preferredCategory"] = "technology"

        val found = store.find("user-001", 1)!!
        assertFailsWith<UnsupportedOperationException> {
            (found.attributes as MutableMap).clear()
        }
        assertEquals("travel", store.find("user-001", 1)?.attributes?.get("preferredCategory"))
        assertEquals(snapshot, store.latestEligible("user-001", snapshot.committedAt))
    }

    @Test
    fun `decision boundary is inclusive and excludes future history`() {
        val store = InMemoryContextStore()
        val first = context(1, "2026-01-01T00:00:01Z")
        val second = context(2, "2026-01-01T00:00:02Z")
        store.save(second)
        store.save(first)

        assertNull(store.latestEligible(first.id, first.committedAt.minusNanos(1)))
        assertEquals(first, store.latestEligible(first.id, first.committedAt))
        assertEquals(first, store.latestEligible(first.id, second.committedAt.minusNanos(1)))
        assertEquals(second, store.latestEligible(first.id, second.committedAt))
    }

    @Test
    fun `equal commit timestamps select highest version regardless of insertion order`() {
        for (order in listOf(listOf(1L, 3L, 2L), listOf(3L, 2L, 1L))) {
            val store = InMemoryContextStore()
            order.forEach { store.save(context(it, "2026-01-01T00:00:01Z")) }
            assertEquals(3L, store.latestEligible("user-001",
                Instant.parse("2026-01-01T00:00:01Z"))?.version)
        }
    }

    @Test
    fun `context identities have independent histories and missing lookups return null`() {
        val store = InMemoryContextStore()
        val first = context(1, "2026-01-01T00:00:02Z")
        val other = first.copy(id = "user-002", committedAt = first.committedAt.minusSeconds(1))
        store.save(first)
        store.save(other)

        assertEquals(first, store.find(first.id, 1))
        assertEquals(other, store.find(other.id, 1))
        assertEquals(other, store.latestEligible(other.id, first.committedAt))
        assertNull(store.latestEligible(first.id, other.committedAt))
        assertNull(store.find(first.id, 2))
        assertNull(store.find("missing", 1))
        assertNull(store.latestEligible("missing", first.committedAt))
    }

    @Test
    fun `rejected writes preserve history and permit a valid retry`() {
        val store = InMemoryContextStore()
        val first = context(1, "2026-01-01T00:00:02Z")
        val third = context(3, "2026-01-01T00:00:04Z")
        store.save(first)
        store.save(third)

        for (invalid in listOf(first.copy(attributes = mapOf("x" to "changed")),
            context(2, "2026-01-01T00:00:01Z"), context(2, "2026-01-01T00:00:05Z"))) {
            assertFailsWith<IllegalArgumentException> { store.save(invalid) }
            assertEquals(first, store.find(first.id, 1))
            assertEquals(third, store.find(third.id, 3))
            assertNull(store.find(first.id, 2))
        }
        val second = context(2, "2026-01-01T00:00:03Z")
        store.save(second)
        assertEquals(second, store.latestEligible(second.id, second.committedAt))
    }

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
