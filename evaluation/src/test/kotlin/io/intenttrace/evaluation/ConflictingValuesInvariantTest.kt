package io.intenttrace.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConflictingValuesInvariantTest {
    private val primary = SourceObservation("primary", 7, "500")
    private val agreeing = SourceObservation("cache", 7, "500")
    private val diverging = SourceObservation("cache", 8, "750")
    private val preferPrimary = ConflictResolution("prefer-primary", "primary")

    @Test fun `agreeing sources need no resolution record`() {
        val result = ConflictingValuesInvariant().evaluate(listOf(primary, agreeing), "cache", null)
        assertEquals(InvariantStatus.SATISFIED, result.status)
        assertEquals(false, result.conflict)
        assertEquals(FailureClass.CONFLICTING_VALUES, result.failureClass)
        assertNull(result.detected)
    }

    @Test fun `silent pick violates and recorded resolution satisfies`() {
        for (detected in listOf(null, false, true)) {
            val result = ConflictingValuesInvariant().evaluate(listOf(primary, diverging), "cache", null, detected)
            assertEquals(InvariantStatus.VIOLATED, result.status)
            assertEquals("750", result.usedValue)
            assertEquals(detected, result.detected)
        }
        assertEquals(InvariantStatus.SATISFIED,
            ConflictingValuesInvariant().evaluate(listOf(primary, diverging), "primary", preferPrimary).status)
    }

    @Test fun `a recorded rule that the decision did not follow still violates`() {
        assertEquals(InvariantStatus.VIOLATED,
            ConflictingValuesInvariant().evaluate(listOf(primary, diverging), "cache", preferPrimary).status)
    }

    @Test fun `conflicts are value disagreements regardless of version`() {
        val sameVersion = SourceObservation("cache", 7, "750")
        assertEquals(InvariantStatus.VIOLATED,
            ConflictingValuesInvariant().evaluate(listOf(primary, sameVersion), "cache", null).status)
    }

    @Test fun `malformed evidence fails immediately`() {
        assertFailsWith<IllegalArgumentException> { SourceObservation("", 7, "500") }
        assertFailsWith<IllegalArgumentException> { SourceObservation("primary", 0, "500") }
        assertFailsWith<IllegalArgumentException> { ConflictResolution("", "primary") }
        assertFailsWith<IllegalArgumentException> { ConflictingValuesInvariant().evaluate(listOf(primary), "primary", null) }
        assertFailsWith<IllegalArgumentException> {
            ConflictingValuesInvariant().evaluate(listOf(primary, primary), "primary", null)
        }
        assertFailsWith<IllegalArgumentException> {
            ConflictingValuesInvariant().evaluate(listOf(primary, diverging), "replica", null)
        }
        assertFailsWith<IllegalArgumentException> {
            ConflictingValuesInvariant().evaluate(listOf(primary, diverging), "primary",
                ConflictResolution("prefer-replica", "replica"))
        }
    }
}
