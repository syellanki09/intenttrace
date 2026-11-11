package io.intenttrace.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReplayInvariantTest {
    private val deliveries = listOf(ReplayEvent("a", 3), ReplayEvent("b", 5), ReplayEvent("a", 3))

    @Test fun `unique contribution survives duplicate delivery`() {
        val result = ReplayInvariant().evaluate(deliveries, listOf("a", "b"), 8)
        assertEquals(InvariantStatus.SATISFIED, result.status)
        assertEquals(8L, result.expectedTotal)
        assertEquals(FailureClass.DUPLICATE_REPLAY, result.failureClass)
        assertNull(result.detected)
    }

    @Test fun `replay violates ground truth independently of detection`() {
        for (detected in listOf(null, false, true)) {
            val result = ReplayInvariant().evaluate(deliveries, listOf("a", "b", "a"), 11, detected)
            assertEquals(InvariantStatus.VIOLATED, result.status)
            assertEquals(detected, result.detected)
        }
        assertEquals(InvariantStatus.SATISFIED,
            ReplayInvariant().evaluate(deliveries, listOf("a", "b"), 8, true).status)
    }

    @Test fun `correct total does not hide repeated or missing contributions`() {
        assertEquals(InvariantStatus.VIOLATED,
            ReplayInvariant().evaluate(deliveries, listOf("a", "a"), 8).status)
        assertEquals(InvariantStatus.VIOLATED,
            ReplayInvariant().evaluate(deliveries, listOf("a", "b"), 9).status)
    }

    @Test fun `invalid or conflicting evidence fails immediately`() {
        assertFailsWith<IllegalArgumentException> { ReplayEvent("", 3) }
        assertFailsWith<IllegalArgumentException> { ReplayEvent("a", 0) }
        assertFailsWith<IllegalArgumentException> { ReplayInvariant().evaluate(emptyList(), emptyList(), 0) }
        assertFailsWith<IllegalArgumentException> { ReplayInvariant().evaluate(deliveries, listOf("unknown"), 3) }
        assertFailsWith<IllegalArgumentException> {
            ReplayInvariant().evaluate(deliveries + ReplayEvent("a", 4), listOf("a", "b"), 8)
        }
        assertFailsWith<ArithmeticException> {
            ReplayInvariant().evaluate(listOf(ReplayEvent("a", Long.MAX_VALUE), ReplayEvent("b", 1)),
                listOf("a", "b"), 0)
        }
    }
}
