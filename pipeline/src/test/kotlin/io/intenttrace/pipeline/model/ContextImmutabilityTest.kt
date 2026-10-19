package io.intenttrace.pipeline.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class ContextImmutabilityTest {
    private val timestamp = Instant.parse("2026-01-01T00:00:01Z")

    @Test
    fun `constructor snapshots input and keeps hash collection membership stable`() {
        val attributes = mutableMapOf("preferredCategory" to "travel")
        val context = Context("user-001", 1, timestamp, attributes)
        val contexts = hashSetOf(context)

        attributes["preferredCategory"] = "technology"
        attributes["extra"] = "value"
        attributes.clear()

        assertEquals(mapOf("preferredCategory" to "travel"), context.attributes)
        assertEquals(true, context in contexts)
    }

    @Test
    fun `exposed attributes and entry views reject mutation`() {
        val context = Context("user-001", 1, timestamp, mapOf("category" to "travel"))

        assertFailsWith<UnsupportedOperationException> {
            (context.attributes as MutableMap)["category"] = "technology"
        }
        assertFailsWith<UnsupportedOperationException> {
            (context.attributes.entries.first() as MutableMap.MutableEntry).setValue("technology")
        }
        assertEquals("travel", context.attributes["category"])
    }

    @Test
    fun `copy snapshots replacement attributes and preserves value operations`() {
        val original = Context("user-001", 1, timestamp, mapOf("category" to "travel"))
        val equivalent = original.copy()
        assertNotSame(original, equivalent)
        assertEquals(original, equivalent)
        assertEquals(original.hashCode(), equivalent.hashCode())
        assertEquals(original.toString(), equivalent.toString())

        val replacement = mutableMapOf("category" to "technology")
        val copy = original.copy(id = "user-002", version = 2,
            committedAt = timestamp.plusSeconds(1), attributes = replacement)
        replacement.clear()

        val (id, version, committedAt, attributes) = copy
        assertEquals("user-002", id)
        assertEquals(2L, version)
        assertEquals(timestamp.plusSeconds(1), committedAt)
        assertEquals(mapOf("category" to "technology"), attributes)
        assertEquals("travel", original.attributes["category"])
        assertFailsWith<IllegalArgumentException> { original.copy(id = "") }
        assertFailsWith<IllegalArgumentException> { original.copy(version = 0) }
    }
}
